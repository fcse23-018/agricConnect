package bw.agriconnect.data.local

import android.app.Application
import androidx.room.Room
import bw.agriconnect.data.local.entity.FarmPlotEntity
import bw.agriconnect.data.local.entity.SatelliteOverviewCacheEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * In-memory Room tests for the local persistence layer.
 *
 * Robolectric supplies the Android [android.content.Context] that `Room.inMemoryDatabaseBuilder`
 * needs while keeping the SQLite engine in-process; no emulator, no on-disk database. Each
 * test gets a fresh in-memory database so cases cannot leak state into one another.
 *
 * `sdk = [34]` pins Robolectric to a packaged Android runtime (the project's `targetSdk` is 35).
 * `application = Application::class` overrides the manifest's `AgriConnectApp`, which is
 * provisioned by another task; the persistence layer needs only a bare [Context], so the
 * test stays independent of the app wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgriDatabaseTest {

    private lateinit var db: AgriDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AgriDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun farmPlot_roundTrips_throughEnumConverters() = runTest {
        val plot = FarmPlot(
            plotId = "plot-1",
            farmerId = "farmer-1",
            plotName = "North Field",
            areaHectares = 2.5,
            geoJsonBoundary = """{"type":"Polygon","coordinates":[]}""",
            notes = "irrigated",
            imageUrl = "/uploads/north-field.jpg",
            createdAt = "2026-01-01T08:00:00Z",
            updatedAt = "2026-01-02T09:30:00Z",
            syncStatus = SyncStatus.PENDING,
            clientId = "client-abc",
        )

        db.farmPlotDao().upsertOne(FarmPlotEntity.fromDomain(plot))
        val loaded = db.farmPlotDao().findById(plot.plotId)!!.toDomain()

        assertThat(loaded).isEqualTo(plot)
        // The enum must survive as its name, not its ordinal.
        assertThat(loaded.syncStatus).isEqualTo(SyncStatus.PENDING)
        assertThat(loaded.clientId).isEqualTo("client-abc")
        assertThat(loaded.imageUrl).isEqualTo("/uploads/north-field.jpg")
    }

    @Test
    fun insertAndEnqueue_createsExactlyOnePendingQueueItem() = runTest {
        val plot = samplePlot("plot-2")
        val item = SyncQueueEntity(
            queueId = "queue-1",
            entityType = SyncQueueEntity.EntityTypes.FARM_PLOT,
            entityId = plot.plotId,
            operation = SyncOperation.CREATE,
            payload = """{"plotName":"North Field"}""",
            createdAt = 1_700_000_000_000L,
        )

        db.farmPlotDao().insertAndEnqueue(FarmPlotEntity.fromDomain(plot), item)

        val pending = db.syncQueueDao().pendingItems()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().status).isEqualTo(SyncStatus.PENDING)
        assertThat(pending.single().operation).isEqualTo(SyncOperation.CREATE)
        assertThat(db.syncQueueDao().countAll()).isEqualTo(1)
        assertThat(db.farmPlotDao().findById(plot.plotId)).isNotNull()
    }

    @Test
    fun pendingCount_tracksQueue_andDropsAfterMarkSynced() = runTest {
        val queueDao = db.syncQueueDao()
        queueDao.insert(sampleQueueItem(queueId = "q-a", createdAt = 1L))
        queueDao.insert(sampleQueueItem(queueId = "q-b", createdAt = 2L))

        assertThat(queueDao.pendingCount().first()).isEqualTo(2)

        queueDao.markSynced("q-a")

        assertThat(queueDao.pendingCount().first()).isEqualTo(1)
        assertThat(queueDao.pendingItems().map { it.queueId }).containsExactly("q-b")
        // SYNCED rows are retained for audit until housekeeping runs.
        assertThat(queueDao.countAll()).isEqualTo(2)
    }

    @Test
    fun deleting_setsPendingDelete_andEnqueuesDeleteOperation() = runTest {
        val plot = samplePlot("plot-3")
        db.farmPlotDao().upsertOne(FarmPlotEntity.fromDomain(plot))

        val deleteItem = SyncQueueEntity(
            queueId = "queue-delete",
            entityType = SyncQueueEntity.EntityTypes.FARM_PLOT,
            entityId = plot.plotId,
            operation = SyncOperation.DELETE,
            payload = """{"plotId":"${plot.plotId}"}""",
            createdAt = 1_700_000_000_100L,
        )
        db.farmPlotDao().markPendingDeleteAndEnqueue(
            plotId = plot.plotId,
            updatedAt = "2026-03-01T00:00:00Z",
            item = deleteItem,
        )

        val tombstoned = db.farmPlotDao().findById(plot.plotId)!!
        assertThat(tombstoned.pendingDelete).isTrue()
        assertThat(tombstoned.syncStatus).isEqualTo(SyncStatus.PENDING)
        // The tombstone is hidden from UI observers immediately...
        assertThat(db.farmPlotDao().observeAll().first()).isEmpty()
        // ...but the DELETE op is queued.
        val pending = db.syncQueueDao().pendingItems()
        assertThat(pending.single().operation).isEqualTo(SyncOperation.DELETE)
    }

    @Test
    fun insertedRow_isPersisted_andReadableViaAnotherAccessPath() = runTest {
        val plot = samplePlot("plot-4")
        db.farmPlotDao().upsertOne(FarmPlotEntity.fromDomain(plot))

        // Another DAO reference obtained from the same database reads the row back.
        val secondDao = db.farmPlotDao()
        val viaSecondDao = secondDao.findById(plot.plotId)!!.toDomain()
        assertThat(viaSecondDao).isEqualTo(plot)

        // And the row genuinely lives in SQLite, not just in a DAO cache: read it with a
        // raw cursor straight off the same in-memory connection.
        db.openHelper.writableDatabase
            .query("SELECT plot_name, sync_status FROM farm_plots WHERE plot_id = ?", arrayOf(plot.plotId))
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("North Field")
                assertThat(cursor.getString(1)).isEqualTo("PENDING")
            }
    }

    // --- satellite overview cache (v2 → v3) ---------------------------------

    @Test
    fun satelliteOverviewCache_roundTrips_andKeepsObservationDateSeparateFromCacheTime() = runTest {
        val dao = db.satelliteOverviewCacheDao()
        val entity = SatelliteOverviewCacheEntity(
            plotId = "plot-cache",
            farmerId = "farmer-1",
            plotName = "North Field",
            observedOn = "2026-10-22",
            latestNdvi = 0.31,
            source = "SENTINEL-2",
            isSimulated = false,
            overviewJson = """{"plot":{"plotId":"plot-cache"}}""",
            cachedAt = 1_700_000_000_000L,
        )

        dao.upsert(entity)
        val loaded = dao.findForPlot("plot-cache")!!

        assertThat(loaded.observedOn).isEqualTo("2026-10-22")
        assertThat(loaded.cachedAt).isEqualTo(1_700_000_000_000L)
        assertThat(loaded.latestNdvi).isEqualTo(0.31)
        // The two timestamps must never be conflated: one is a satellite pass, one is a
        // local write. If these were ever equal by construction the honesty rule would be
        // untestable.
        assertThat(loaded.observedOn).isNotEqualTo(loaded.cachedAt.toString())
    }

    @Test
    fun satelliteOverviewCache_upsertReplacesTheRow_forOnePlot() = runTest {
        val dao = db.satelliteOverviewCacheDao()
        dao.upsert(
            SatelliteOverviewCacheEntity(
                plotId = "plot-cache",
                farmerId = "farmer-1",
                observedOn = "2026-10-01",
                overviewJson = "{}",
                cachedAt = 1L,
            ),
        )
        dao.upsert(
            SatelliteOverviewCacheEntity(
                plotId = "plot-cache",
                farmerId = "farmer-1",
                observedOn = "2026-10-22",
                overviewJson = "{}",
                cachedAt = 2L,
            ),
        )

        assertThat(dao.findForFarmer("farmer-1")).hasSize(1)
        assertThat(dao.findForPlot("plot-cache")!!.observedOn).isEqualTo("2026-10-22")
    }

    @Test
    fun satelliteOverviewCache_observeForPlot_emitsOnWrite() = runTest {
        val dao = db.satelliteOverviewCacheDao()
        assertThat(dao.observeForPlot("plot-cache").first()).isNull()

        dao.upsert(
            SatelliteOverviewCacheEntity(
                plotId = "plot-cache",
                farmerId = "farmer-1",
                observedOn = "2026-10-22",
                overviewJson = "{}",
                cachedAt = 5L,
            ),
        )

        assertThat(dao.observeForPlot("plot-cache").first()?.observedOn).isEqualTo("2026-10-22")
    }

    @Test
    fun satelliteOverviewCache_deleteForFarmer_clearsEveryPlotOfThatOwner() = runTest {
        val dao = db.satelliteOverviewCacheDao()
        dao.upsert(cacheRow("plot-a", "farmer-1"))
        dao.upsert(cacheRow("plot-b", "farmer-1"))
        dao.upsert(cacheRow("plot-c", "farmer-2"))

        dao.deleteForFarmer("farmer-1")

        assertThat(dao.findForFarmer("farmer-1")).isEmpty()
        // Another owner's cache is untouched — the DAO must never be able to wipe it.
        assertThat(dao.findForFarmer("farmer-2")).hasSize(1)
    }

    // --- fixtures -----------------------------------------------------------

    private fun cacheRow(plotId: String, farmerId: String) = SatelliteOverviewCacheEntity(
        plotId = plotId,
        farmerId = farmerId,
        observedOn = "2026-10-22",
        overviewJson = "{}",
        cachedAt = 1L,
    )

    private fun samplePlot(plotId: String) = FarmPlot(
        plotId = plotId,
        farmerId = "farmer-1",
        plotName = "North Field",
        areaHectares = 1.0,
        geoJsonBoundary = null,
        createdAt = "2026-01-01T08:00:00Z",
        updatedAt = "2026-01-01T08:00:00Z",
        syncStatus = SyncStatus.PENDING,
        clientId = "client-$plotId",
    )

    private fun sampleQueueItem(queueId: String, createdAt: Long) = SyncQueueEntity(
        queueId = queueId,
        entityType = SyncQueueEntity.EntityTypes.FARM_PLOT,
        entityId = "plot-x",
        operation = SyncOperation.CREATE,
        payload = "{}",
        createdAt = createdAt,
    )
}
