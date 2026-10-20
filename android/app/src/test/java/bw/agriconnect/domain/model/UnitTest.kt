package bw.agriconnect.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Listing units, including the livestock group units.
 *
 * The app's unit list is a mirror of the server's, and a listing carries the unit as
 * its wire name. If the two lists drift, a farmer creates a listing that looks fine
 * and then fails to publish — so the names, the round-trip and the livestock split
 * are all pinned here, off-device.
 */
class UnitTest {

    @Test
    fun `the wire name is the lower-cased enum name`() {
        assertThat(Unit.KG.wire).isEqualTo("kg")
        assertThat(Unit.TONNE.wire).isEqualTo("tonne")
        assertThat(Unit.HEAD.wire).isEqualTo("head")
        assertThat(Unit.HERD.wire).isEqualTo("herd")
        assertThat(Unit.FLOCK.wire).isEqualTo("flock")
    }

    @Test
    fun `the livestock group units exist`() {
        assertThat(Unit.entries.map { it.name }).containsAtLeast("HEAD", "HERD", "FLOCK")
    }

    @Test
    fun `only the animal units are marked livestock`() {
        val livestock = Unit.entries.filter { it.livestock }.map { it.name }
        assertThat(livestock).containsExactly("HEAD", "HERD", "FLOCK")

        // Produce is sold by measure, so none of these may be livestock.
        for (name in listOf("KG", "TONNE", "BAG", "CRATE", "BUNCH", "LITRE")) {
            assertThat(Unit.valueOf(name).livestock).isFalse()
        }
    }

    @Test
    fun `every wire name round-trips back to the same unit`() {
        // The value sent to the server must come back as the same unit, or the
        // offline copy would show a different unit after a refresh.
        for (unit in Unit.entries) {
            assertThat(Unit.fromWire(unit.wire)).isEqualTo(unit)
        }
    }

    @Test
    fun `fromWire is case-insensitive and falls back to kg`() {
        assertThat(Unit.fromWire("HERD")).isEqualTo(Unit.HERD)
        assertThat(Unit.fromWire("Flock")).isEqualTo(Unit.FLOCK)
        // An unknown or absent unit must not crash a listing render.
        assertThat(Unit.fromWire("cattle")).isEqualTo(Unit.KG)
        assertThat(Unit.fromWire(null)).isEqualTo(Unit.KG)
    }

    @Test
    fun `the wire names match the server list exactly`() {
        // Kept identical to LISTING_UNITS in the backend schema. If this fails, the
        // server list changed and the app must follow.
        assertThat(Unit.entries.map { it.wire })
            .containsExactly("kg", "tonne", "bag", "crate", "bunch", "litre", "head", "herd", "flock")
    }
}
