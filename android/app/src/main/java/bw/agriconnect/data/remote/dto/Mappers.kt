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
import bw.agriconnect.domain.model.NdviAction
import bw.agriconnect.domain.model.NdviAdvice
import bw.agriconnect.domain.model.NdviAdviceContext
import bw.agriconnect.domain.model.NdviAdvicePriority
import bw.agriconnect.domain.model.NdviConfidence
import bw.agriconnect.domain.model.NdviProcessingStatus
import bw.agriconnect.domain.model.NdviScaleRow
import bw.agriconnect.domain.model.NdviTrend
import bw.agriconnect.domain.model.NdviTrendDirection
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
import bw.agriconnect.domain.model.satellite.*
import bw.agriconnect.core.util.GeoVertex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    imageUrl = imageUrl,
    createdAt = createdAt,
    updatedAt = updatedAt,
    clientId = clientId,
)

fun FarmPlot.toCreateRequest(): CreateFarmPlotRequest = CreateFarmPlotRequest(
    plotName = plotName,
    areaHectares = areaHectares,
    geoJsonBoundary = geoJsonBoundary.asGeoJsonElement(),
    imageUrl = imageUrl,
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
    advice = advice?.toDomain(),
)

/**
 * The advisory block is dropped entirely when it carries no assessment. Without a
 * headline and a meaning there is nothing to tell the farmer, and an empty advice card
 * would be worse than no card at all — the screen then simply shows the reading.
 */
fun NdviAdviceDto.toDomain(): NdviAdvice? {
    val a = assessment ?: return null
    return NdviAdvice(
        headline = a.headline.orEmpty(),
        meaning = a.meaning.orEmpty(),
        summary = a.summary.orEmpty(),
        priority = enumOrDefault(priority, NdviAdvicePriority.ROUTINE),
        label = a.label.orEmpty(),
        colour = a.colour.orEmpty(),
        actions = actions.mapNotNull { it.toDomain() },
        watchFor = watchFor.filter { it.isNotBlank() },
        trend = trend?.let {
            NdviTrend(
                direction = enumOrDefault(it.direction, NdviTrendDirection.FIRST_READING),
                delta = it.delta,
                previousValue = it.previousValue,
                previousDate = it.previousDate,
                note = it.note,
            )
        },
        context = context?.let {
            NdviAdviceContext(
                plotName = it.plotName,
                areaHectares = it.areaHectares,
                observedOn = it.observedOn,
                daysSinceObservation = it.daysSinceObservation,
                cloudCoverPercent = it.cloudCoverPercent,
                source = it.source,
                sourceIsSimulated = it.sourceIsSimulated,
            )
        },
        confidence = confidence?.let {
            NdviConfidence(level = it.level.orEmpty(), note = it.note.orEmpty())
        },
        scaleGuide = scaleGuide.mapNotNull { row ->
            row.range?.takeIf { it.isNotBlank() }?.let { range -> NdviScaleRow(range, row.meaning.orEmpty()) }
        },
        limits = limits.filter { it.isNotBlank() },
        disclaimer = disclaimer,
    )
}

/** An action without a title cannot be acted on, so it is dropped rather than shown blank. */
private fun NdviActionDto.toDomain(): NdviAction? {
    val t = title?.takeIf { it.isNotBlank() } ?: return null
    return NdviAction(title = t, detail = detail.orEmpty())
}

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

/* --------------------------------- satellite monitoring -------------------- */

/** Helper to parse a Map<String, Any> from JsonObject for flexible JSON fields. */
private fun JsonObject?.toAnyMap(): Map<String, Any?>? =
    this?.let { obj ->
        obj.entries.associate { (key, element) ->
            key to elementToAny(element)
        }
    }

private fun elementToAny(element: JsonElement): Any? = when (element) {
    is JsonPrimitive -> {
        if (element.content.isNotEmpty() && element.content.all { it.isDigit() || it in ".-" }) {
            element.content.toDoubleOrNull() ?: element.content.toLongOrNull() ?: element.content
        } else if (element.content.equals("true", ignoreCase = true) || element.content.equals("false", ignoreCase = true)) {
            element.content.toBoolean()
        } else {
            element.content
        }
    }
    is JsonObject -> element.toAnyMap()
    is kotlinx.serialization.json.JsonArray -> element.map { elementToAny(it) }
    else -> element.toString()
}

/** Fallback for unknown classification values — never crash, always fall back to UNCLEAR. */
private fun parseClassification(value: String?): VegetationDropClassification =
    when (value?.uppercase()) {
        "TRANSIENT_DIP", "NOISE" -> VegetationDropClassification.TRANSIENT_DIP
        "POSSIBLE_HARVEST" -> VegetationDropClassification.POSSIBLE_HARVEST
        "POSSIBLE_HEAT_OR_DRY_STRESS", "POSSIBLE_HEAT_STRESS" ->
            VegetationDropClassification.POSSIBLE_HEAT_OR_DRY_STRESS
        "GRADUAL_DECLINE" -> VegetationDropClassification.GRADUAL_DECLINE
        else -> VegetationDropClassification.UNCLEAR
    }

/** Fallback for unknown event kind — default to OTHER. */
private fun parseEventKind(value: String?): PlotEventKind =
    when (value?.uppercase()) {
        "DETECTED_DROP" -> PlotEventKind.DETECTED_DROP
        "HARVEST" -> PlotEventKind.HARVEST
        "CLEARING" -> PlotEventKind.CLEARING
        "HEAT_STRESS" -> PlotEventKind.HEAT_STRESS
        "WATER_STRESS" -> PlotEventKind.WATER_STRESS
        "OBSERVATION" -> PlotEventKind.OBSERVATION
        else -> PlotEventKind.OTHER
    }

/** Fallback for unknown event source — default to FARMER. */
private fun parseEventSource(value: String?): PlotEventSource =
    when (value?.uppercase()) {
        "SYSTEM" -> PlotEventSource.SYSTEM
        else -> PlotEventSource.FARMER
    }

/** Fallback for unknown alert severity — default to INFO. */
private fun parseAlertSeverity(value: String?): AlertSeverity =
    when (value?.uppercase()) {
        "WARNING" -> AlertSeverity.WARNING
        "CRITICAL" -> AlertSeverity.CRITICAL
        else -> AlertSeverity.INFO
    }

/** Fallback for unknown alert status — default to OPEN. */
private fun parseAlertStatus(value: String?): AlertStatus =
    when (value?.uppercase()) {
        "ACKNOWLEDGED" -> AlertStatus.ACKNOWLEDGED
        "DISMISSED" -> AlertStatus.DISMISSED
        else -> AlertStatus.OPEN
    }

/** Fallback for unknown confidence — default to LOW. */
private fun parseConfidence(value: String?): ConfidenceLevel =
    when (value?.uppercase()) {
        "HIGH" -> ConfidenceLevel.HIGH
        "MEDIUM" -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }

/** Fallback for unknown geometry method. */
private fun parseGeometryMethod(value: String?): GeometryMethod =
    when (value?.uppercase()) {
        "RECTANGLE" -> GeometryMethod.RECTANGLE
        "POLYGON" -> GeometryMethod.POLYGON
        else -> GeometryMethod.UNKNOWN
    }

/** Fallback for unknown observation quality. */
private fun parseQuality(value: String?): ObservationQuality =
    when (value?.uppercase()) {
        "FAIR" -> ObservationQuality.FAIR
        "POOR" -> ObservationQuality.POOR
        else -> ObservationQuality.GOOD
    }

fun SatelliteOverviewDto.toDomain(): SatelliteOverview = SatelliteOverview(
    plot = plot.toPlotDomain(),
    geometry = geometry.toDomain(),
    observationStatus = observationStatus.toDomain(),
    // The monitoring series carries no row ids: key each row by plot+date so the
    // chart and list stay stable. `farmerId` is not part of this payload.
    observations = observations.map { it.toDomain(plotId = plot.plotId, farmerId = "") },
    summary = MonitoringSummary(
        ndvi = summary.toNdviDomain(),
        evi = eviSummary?.toEviDomain(),
    ),
    growthCycle = growthCycle?.toDomain(),
    drops = drops.map { it.toDomain() },
    weather = weather?.toDomain(),
    alerts = alerts.map { it.toDomain() },
    quality = quality?.toDomain(),
    modelStatus = modelStatus?.toDomain(),
    thresholds = thresholds,
    thresholdDocumentation = thresholdDocumentation,
    disclaimer = disclaimer,
    generatedAt = generatedAt,
)

/**
 * The monitoring endpoints return a compact plot reference (no owner, no
 * timestamps). Those fields are owned by the farms endpoints and are unused on
 * the monitoring screen, so they are filled with empty placeholders rather than
 * fabricated values.
 */
fun PlotRefDto.toPlotDomain(): FarmPlot = FarmPlot(
    plotId = plotId,
    farmerId = "",
    plotName = plotName,
    areaHectares = areaHectares,
    geoJsonBoundary = geoJsonBoundary?.toString(),
    centroidLat = centroidLat,
    centroidLng = centroidLng,
    imageUrl = imageUrl,
    createdAt = "",
    updatedAt = "",
)

fun PlotGeometryDto.toDomain(): PlotGeometry = PlotGeometry(
    method = parseGeometryMethod(method),
    vertices = vertices.map { GeoVertex(latitude = it.lat, longitude = it.lon) },
    areaM2 = areaM2,
    areaHectares = areaHectares,
    perimeterM = perimeterM,
    centroid = centroid?.let { GeoVertex(latitude = it.lat, longitude = it.lon) },
    sideLengthsM = sideLengthsM,
    valid = valid,
    reason = reason,
)

fun ObservationStatusDto.toDomain(): ObservationStatus = ObservationStatus(
    isCached = isCached,
    isSimulated = isSimulated,
    newestObservation = newestObservation,
    newestObservationAgeDays = newestObservationAgeDays,
    provider = provider?.source,
    providerError = providerError,
    note = note,
    returned = returned,
    coversDays = coversDays,
    boundaryUsable = boundaryUsable,
)

fun PlotObservationDto.toDomain(plotId: String, farmerId: String): PlotObservation = PlotObservation(
    observationId = "$plotId:$date",
    plotId = plotId,
    farmerId = farmerId,
    observedOn = date,
    ndvi = ndvi,
    evi = evi,
    smoothedNdvi = smoothedNdvi,
    smoothedEvi = smoothedEvi,
    validPixelPct = validPct,
    cloudCoverPct = cloudPct,
    pixelCount = pixels,
    quality = parseQuality(quality),
    isOutlier = isOutlier,
    isSimulated = isSimulated,
    source = source,
)

fun IndexStatsDto.toNdviDomain(): NdviMonitoringSummary = NdviMonitoringSummary(
    available = available,
    count = count,
    min = min,
    max = max,
    avg = average,
    latest = latest?.ndvi,
    latestDate = latest?.date ?: lastDate,
    changeVsPrevious = change,
    changeDate = previous?.date,
    trend = trend,
    dataAgeDays = dataAgeDays,
)

fun IndexStatsDto.toEviDomain(): EviMonitoringSummary = EviMonitoringSummary(
    available = available,
    count = count,
    min = min,
    max = max,
    avg = average,
    latest = latest?.evi,
    latestDate = latest?.date ?: lastDate,
    changeVsPrevious = change,
    changeDate = previous?.date,
    trend = trend,
    dataAgeDays = dataAgeDays,
)

fun GrowthCycleDto.toDomain(): GrowthCycle = GrowthCycle(
    available = available,
    reason = reason,
    greenUp = greenUpOn,
    peak = peakOn,
    endOfSeason = endOn,
    peakValue = peakValue,
    baseline = baseline,
    amplitude = amplitude,
    confidence = if (available) parseConfidence(confidence) else null,
    strategy = strategy,
    evidence = evidence,
)

fun VegetationDropDto.toDomain(): VegetationDrop {
    // The server reports both index pairs; a drop is measured on NDVI when that
    // pair exists, otherwise on EVI.
    val onNdvi = ndviBefore != null || ndviAfter != null
    return VegetationDrop(
        date = date,
        previousDate = previousDate,
        daysBetween = daysBetween,
        index = if (onNdvi) VegetationIndex.NDVI else VegetationIndex.EVI,
        magnitude = magnitude,
        valueBefore = if (onNdvi) ndviBefore else eviBefore,
        valueAfter = if (onNdvi) ndviAfter else eviAfter,
        relativePct = relativePct,
        classification = if (label == null && classification == null) null else parseClassification(classification),
        label = label,
        explanation = explanation,
        evidence = evidence.toEvidenceList(),
        confidence = confidence?.let { parseConfidence(it) },
        nextStep = nextStep,
        disclaimer = disclaimer,
        weatherContext = (weatherContext as? JsonObject)?.toTypedMap(),
        recoveryFraction = recoveryFraction,
        recoveredOn = recoveredOn,
        gradual = gradual,
        detectedEventId = detectedEventId,
    )
}

fun PlotEventDto.toDomain(): PlotEvent = PlotEvent(
    eventId = eventId,
    plotId = plotId,
    farmerId = farmerId,
    eventDate = eventDate,
    kind = parseEventKind(kind),
    source = parseEventSource(source),
    classification = classification,
    confirmed = confirmed,
    notes = notes,
    ndviBefore = ndviBefore,
    ndviAfter = ndviAfter,
    magnitude = magnitude,
    confidence = confidence?.let { parseConfidence(it) },
    evidence = evidence?.toTypedMap(),
    weatherContext = weatherContext?.toTypedMap(),
    recordedBy = recordedBy,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PlotAlertDto.toDomain(): PlotAlert = PlotAlert(
    alertId = alertId,
    plotId = plotId,
    farmerId = farmerId,
    alertKey = alertKey,
    alertType = alertType,
    severity = parseAlertSeverity(severity),
    title = title,
    body = body,
    status = parseAlertStatus(status),
    raisedAt = raisedAt,
    resolvedAt = resolvedAt,
    acknowledgedAt = acknowledgedAt,
    dismissedAt = dismissedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun WeatherResponseDto.toDomain(): WeatherData? = weather?.toDomain()

fun WeatherDataDto.toDomain(): WeatherData = WeatherData(
    available = available,
    reason = reason,
    source = source,
    dataType = when (dataType?.uppercase()) {
        "MEASURED" -> WeatherDataType.MEASURED
        "ESTIMATED" -> WeatherDataType.ESTIMATED
        else -> WeatherDataType.MODELLED
    },
    daily = daily.map { it.toDomain() },
    summary = summary?.toDomain(),
    note = note,
    fetchedAt = fetchedAt,
    cached = cached,
)

fun WeatherDailyDto.toDomain(): WeatherDaily = WeatherDaily(
    date = date,
    rainfallMm = rainfallMm,
    tempMinC = tempMinC,
    tempMaxC = tempMaxC,
    tempAvgC = tempMeanC,
    soilMoisturePct = soilMoisturePct,
)

fun WeatherSummaryDto.toDomain(): WeatherSummary = WeatherSummary(
    periodStart = periodStart,
    periodEnd = periodEnd,
    rainfall7dMm = rainfall7dMm,
    rainfall14dMm = rainfall14dMm,
    tempMax7dC = tempMax7dC,
    tempMin7dC = tempMin7dC,
    avgTemp7dC = avgTemp7dC,
    soilMoistureMeanPct = soilMoistureMeanPct,
)

fun QualityDto.toDomain(): MonitoringQuality = MonitoringQuality(
    readingsReceived = readingsReceived,
    readingsUsable = readingsUsable,
    readingsRejected = readingsRejected,
    outliers = outliers,
    note = note,
)

fun ModelStatusDto.toDomain(): ModelStatus = ModelStatus(
    trained = trained,
    readyToTrain = readyToTrain,
    labelledEvents = labelledEvents,
    requiredLabels = requiredLabels,
    note = note,
)

fun BoundaryPreviewResponse.toPreviewDomain(): BoundaryPreview = BoundaryPreview(
    geometry = geometry.toDomain(),
    note = note,
)

fun SetBoundaryResponse.toDomain(): SetBoundaryResult = SetBoundaryResult(
    plot = plot.toPlotDomain(),
    geometry = geometry.toDomain(),
    observationsInvalidated = observationsInvalidated,
    note = note,
)

fun PlotObservationSummaryDto.toDomain(): PlotSummary = PlotSummary(
    plotId = plotId,
    observedOn = observedOn,
    ndvi = ndvi,
    evi = evi,
    quality = quality,
    isSimulated = isSimulated,
    source = source,
)

/**
 * A strict object-to-map conversion for the domain models that hold
 * `Map<String, Any>` (event evidence / weather context). Null JSON values are
 * dropped rather than stored as a stringified "null".
 */
private fun JsonObject.toTypedMap(): Map<String, Any> =
    entries.mapNotNull { (key, element) -> elementToAny(element)?.let { key to it } }.toMap()

/**
 * Evidence arrives as an array of sentences for drops, or as an object with an
 * `items` array for stored events. Both render as sentences; anything else is
 * reported as absent rather than stringified into something meaningless.
 */
private fun JsonElement?.toEvidenceList(): List<String> = when (this) {
    is kotlinx.serialization.json.JsonArray -> mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    }
    is JsonObject -> {
        val items = this["items"]
        if (items is kotlinx.serialization.json.JsonArray) {
            items.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            }
        } else {
            val joined = entries.joinToString(", ") { "${it.key}=${elementToAny(it.value)}" }
            if (joined.isBlank()) emptyList() else listOf(joined)
        }
    }
    else -> emptyList()
}
