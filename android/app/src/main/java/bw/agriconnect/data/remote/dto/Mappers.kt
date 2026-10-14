package bw.agriconnect.data.remote.dto

import bw.agriconnect.data.session.AuthTokens
import bw.agriconnect.domain.model.AccountStatus
import bw.agriconnect.domain.model.AppNotification
import bw.agriconnect.domain.model.DeletionRequest
import bw.agriconnect.domain.model.DeletionRequestStatus
import bw.agriconnect.domain.model.Expense
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.FinancePoint
import bw.agriconnect.domain.model.FinanceSummary
import bw.agriconnect.domain.model.HarvestRecord
import bw.agriconnect.domain.model.ListingStatus
import bw.agriconnect.domain.model.LivestockCount
import bw.agriconnect.domain.model.NdviProcessingStatus
import bw.agriconnect.domain.model.NotificationPreferences
import bw.agriconnect.domain.model.Order
import bw.agriconnect.domain.model.OrderStatus
import bw.agriconnect.domain.model.ProduceListing
import bw.agriconnect.domain.model.RetentionItem
import bw.agriconnect.domain.model.SaleTransaction
import bw.agriconnect.domain.model.SatelliteNdvi
import bw.agriconnect.domain.model.StressLevel
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncQueueItem
import bw.agriconnect.domain.model.SyncStatus
import bw.agriconnect.domain.model.Unit as MeasureUnit
import bw.agriconnect.domain.model.User
import bw.agriconnect.domain.model.UserRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DTO → domain mapping.
 *
 * Every enum is decoded defensively. A server that introduces a new status (or a
 * typo) must not crash a client that has not been updated yet — forward
 * compatibility is a hard requirement for an app that ships on a slower cadence
 * than the backend. Unknown values therefore fall back to a safe default rather
 * than throwing.
 */

/* --------------------------------- helpers -------------------------------- */

/**
 * A standalone `Json` for the two places a mapper has to convert between a
 * structured JSON value (the wire) and the opaque string the domain model keeps.
 * It mirrors the network configuration so the round-trip is lossless.
 */
private val mapperJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Structured GeoJSON → the compact JSON string the domain model persists. */
private fun JsonElement?.asGeoJsonString(): String? =
    this?.let { runCatching { mapperJson.encodeToString(JsonElement.serializer(), it) }.getOrNull() }

/** Compact JSON string → the structured value the server expects. */
private fun String?.asGeoJsonElement(): JsonElement? =
    this?.let { runCatching { mapperJson.parseToJsonElement(it) }.getOrNull() }

private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
    if (value == null) default else runCatching { enumValueOf<T>(value) }.getOrElse { default }

private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

/** Epoch millis (the local queue's clock) → the ISO-8601 string the API expects. */
private fun epochMillisToIso(millis: Long): String =
    synchronized(isoFormat) { isoFormat.format(Date(millis)) }

/* ----------------------------------- auth --------------------------------- */

fun AuthResponse.toAuthTokens(): AuthTokens =
    AuthTokens(accessToken = accessToken, refreshToken = refreshToken, expiresIn = expiresIn)

fun TokenPairResponse.toAuthTokens(): AuthTokens =
    AuthTokens(accessToken = accessToken, refreshToken = refreshToken, expiresIn = expiresIn)

/* ----------------------------------- user --------------------------------- */

fun NotificationPreferencesDto.toDomain(): NotificationPreferences = NotificationPreferences(
    orders = orders,
    sync = sync,
    satellite = satellite,
    marketplace = marketplace,
)

fun UserDto.toDomain(): User = User(
    userId = userId,
    fullName = fullName,
    phoneNumber = phoneNumber,
    email = email,
    role = enumOrDefault(role, UserRole.BUYER),
    accountStatus = enumOrDefault(accountStatus, AccountStatus.UNVERIFIED),
    avatarUrl = avatarUrl,
    notificationPreferences = notificationPreferences?.toDomain() ?: NotificationPreferences(),
    verifiedAt = verifiedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    employerFarmerId = employerFarmerId,
)

fun NotificationPreferences.toDto(): NotificationPreferencesDto = NotificationPreferencesDto(
    orders = orders,
    sync = sync,
    satellite = satellite,
    marketplace = marketplace,
)

fun DeletionRequestDto.toDomain(): DeletionRequest = DeletionRequest(
    requestId = requestId,
    status = enumOrDefault(status, DeletionRequestStatus.PENDING),
    requestedAt = requestedAt,
    effectiveAt = effectiveAt,
    retention = retention.map { it.toDomain() },
)

fun RetentionItemDto.toDomain(): RetentionItem = RetentionItem(
    dataCategory = dataCategory,
    justification = justification,
    retainUntil = retainUntil,
)

/* ---------------------------------- farms --------------------------------- */

fun FarmPlotDto.toDomain(): FarmPlot = FarmPlot(
    plotId = plotId,
    farmerId = farmerId,
    plotName = plotName,
    areaHectares = areaHectares,
    geoJsonBoundary = geoJsonBoundary.asGeoJsonString(),
    centroidLat = centroidLat,
    centroidLng = centroidLng,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientId = clientId,
)

fun FarmPlot.toCreateRequest(): CreateFarmPlotRequest = CreateFarmPlotRequest(
    plotName = plotName,
    areaHectares = areaHectares,
    geoJsonBoundary = geoJsonBoundary.asGeoJsonElement(),
    clientId = clientId,
    notes = notes,
)

fun HarvestDto.toDomain(): HarvestRecord = HarvestRecord(
    harvestId = harvestId,
    farmerId = farmerId,
    plotId = plotId,
    cropType = cropType,
    quantityKg = quantityKg,
    harvestDate = harvestDate,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientId = clientId,
)

fun HarvestRecord.toCreateRequest(): CreateHarvestRequest = CreateHarvestRequest(
    cropType = cropType,
    quantityKg = quantityKg,
    harvestDate = harvestDate,
    plotId = plotId,
    notes = notes,
    clientId = clientId,
)

fun LivestockDto.toDomain(): LivestockCount = LivestockCount(
    countId = countId,
    farmerId = farmerId,
    animalType = animalType,
    headCount = headCount,
    recordDate = recordDate,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientId = clientId,
)

fun LivestockCount.toCreateRequest(): CreateLivestockRequest = CreateLivestockRequest(
    animalType = animalType,
    headCount = headCount,
    recordDate = recordDate,
    notes = notes,
    clientId = clientId,
)

/* --------------------------------- finance -------------------------------- */

fun SaleDto.toDomain(): SaleTransaction = SaleTransaction(
    transactionId = transactionId,
    farmerId = farmerId,
    item = item,
    quantity = quantity,
    unitPrice = unitPrice,
    amount = amount,
    currency = currency,
    buyerName = buyerName,
    date = date,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientId = clientId,
)

fun SaleTransaction.toCreateRequest(): CreateSaleRequest = CreateSaleRequest(
    item = item,
    quantity = quantity,
    unitPrice = unitPrice,
    amount = amount,
    currency = currency,
    buyerName = buyerName,
    date = date,
    notes = notes,
    clientId = clientId,
)

fun ExpenseDto.toDomain(): Expense = Expense(
    expenseId = expenseId,
    farmerId = farmerId,
    category = category,
    description = description,
    amount = amount,
    currency = currency,
    expenseDate = expenseDate,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientId = clientId,
)

fun Expense.toCreateRequest(): CreateExpenseRequest = CreateExpenseRequest(
    category = category,
    description = description,
    amount = amount,
    currency = currency,
    expenseDate = expenseDate,
    clientId = clientId,
)

fun FinanceSummaryDto.toDomain(): FinanceSummary = FinanceSummary(
    totalSales = totalSales,
    totalExpenses = totalExpenses,
    netBalance = netBalance,
    currency = currency,
    series = series.map { it.toDomain() },
)

fun FinancePointDto.toDomain(): FinancePoint = FinancePoint(
    period = period,
    sales = sales,
    expenses = expenses,
)

/* --------------------------------- satellite ------------------------------ */

fun NdviDto.toDomain(): SatelliteNdvi = SatelliteNdvi(
    ndviId = ndviId,
    plotId = plotId,
    farmerId = farmerId.orEmpty(),
    satelliteDate = satelliteDate,
    meanNdviValue = meanNdviValue,
    stressLevel = enumOrNull<StressLevel>(stressLevel),
    status = enumOrDefault(status, NdviProcessingStatus.REQUESTED),
    failureReason = failureReason,
    cloudCover = cloudCover,
    compressedVectorJson = compressedVectorJson.asGeoJsonString(),
    isCached = isCached,
    source = source ?: "SENTINEL-2",
    createdAt = createdAt.orEmpty(),
)

/* -------------------------------- marketplace ----------------------------- */

fun ListingDto.toDomain(): ProduceListing = ProduceListing(
    listingId = listingId,
    farmerId = farmerId,
    produce = produce,
    description = description,
    quantity = quantity,
    reservedQty = reservedQty,
    soldQty = soldQty,
    unit = MeasureUnit.fromWire(unit),
    price = price,
    currency = currency,
    status = enumOrDefault(status, ListingStatus.DRAFT),
    imageUrl = imageUrl,
    locationText = locationText,
    publishedAt = publishedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    farmerName = farmerName,
)

fun ProduceListing.toCreateRequest(): CreateListingRequest = CreateListingRequest(
    produce = produce,
    description = description,
    quantity = quantity,
    unit = unit.name.lowercase(Locale.US),
    price = price,
    currency = currency,
    imageUrl = imageUrl,
    locationText = locationText,
)

fun ProduceListing.toUpdateRequest(): UpdateListingRequest = UpdateListingRequest(
    produce = produce,
    description = description,
    quantity = quantity,
    unit = unit.name.lowercase(Locale.US),
    price = price,
    currency = currency,
    imageUrl = imageUrl,
    locationText = locationText,
)

fun OrderDto.toDomain(): Order = Order(
    orderId = orderId,
    listingId = listingId,
    buyerId = buyerId,
    farmerId = farmerId,
    quantity = quantity,
    unitPrice = unitPrice,
    totalPrice = totalPrice,
    currency = currency,
    status = enumOrDefault(status, OrderStatus.PENDING),
    note = note,
    buyerContact = buyerContact,
    produce = produce,
    buyerName = buyerName,
    farmerDecisionAt = farmerDecisionAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/* ------------------------------- notifications ---------------------------- */

fun NotificationDto.toDomain(): AppNotification = AppNotification(
    notificationId = notificationId,
    type = type,
    title = title,
    body = body,
    relatedEntityType = relatedEntityType,
    relatedEntityId = relatedEntityId,
    isRead = isRead,
    createdAt = createdAt,
)

/* ----------------------------------- sync --------------------------------- */

fun SyncQueueItem.toSyncItemDto(): SyncItemDto = SyncItemDto(
    queueId = queueId,
    entityType = entityType,
    entityId = entityId,
    operation = operation.name,
    payload = payload,
    clientTimestamp = epochMillisToIso(createdAt),
    retryCount = retryCount,
)

/** The server echo of a push result as a [SyncStatus] (unknown → `FAILED`). */
fun SyncResultDto.statusEnum(): SyncStatus =
    enumOrDefault(status, SyncStatus.FAILED)

/** A pull delta's write operation, when present. */
fun SyncChangeDto.operationEnum(): SyncOperation? =
    enumOrNull<SyncOperation>(operation)
