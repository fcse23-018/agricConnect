package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire types for plots, harvests, livestock and finance
 * (docs/02-API-CONTRACT.md §2.4 and §2.5).
 *
 * Money fields arrive as JSON numbers with two decimals (backend `serialize.js`)
 * and map to [Double], matching the domain models.
 */

/** A bare `{ "items": [...] }` envelope used by the non-paginated list endpoints. */
@Serializable
data class ItemsResponse<T>(val items: List<T> = emptyList())

/* ----------------------------------- plots -------------------------------- */

@Serializable
data class FarmPlotDto(
    val plotId: String,
    val farmerId: String,
    val plotName: String,
    val areaHectares: Double? = null,
    /** GeoJSON Polygon/MultiPolygon/Feature object, stored verbatim. */
    val geoJsonBoundary: JsonElement? = null,
    val centroidLat: Double? = null,
    val centroidLng: Double? = null,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val clientId: String? = null,
)

@Serializable
data class CreateFarmPlotRequest(
    val plotName: String,
    val areaHectares: Double? = null,
    val geoJsonBoundary: JsonElement? = null,
    val clientId: String? = null,
    val notes: String? = null,
)

/**
 * `PATCH` uses a fully-optional body: with `explicitNulls = false` a null field is
 * omitted from the JSON, which is exactly the partial-update semantics the server
 * expects (it rejects an empty body).
 */
@Serializable
data class UpdateFarmPlotRequest(
    val plotName: String? = null,
    val areaHectares: Double? = null,
    val geoJsonBoundary: JsonElement? = null,
    val notes: String? = null,
)

/* --------------------------------- harvests ------------------------------- */

@Serializable
data class HarvestDto(
    val harvestId: String,
    val farmerId: String,
    val plotId: String? = null,
    val cropType: String,
    val quantityKg: Double,
    /** Date-only (`YYYY-MM-DD`). */
    val harvestDate: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val clientId: String? = null,
)

@Serializable
data class CreateHarvestRequest(
    val cropType: String,
    val quantityKg: Double,
    val harvestDate: String,
    val plotId: String? = null,
    val notes: String? = null,
    val clientId: String? = null,
)

@Serializable
data class UpdateHarvestRequest(
    val cropType: String? = null,
    val quantityKg: Double? = null,
    val harvestDate: String? = null,
    val plotId: String? = null,
    val notes: String? = null,
)

/* --------------------------------- livestock ------------------------------ */

@Serializable
data class LivestockDto(
    val countId: String,
    val farmerId: String,
    val animalType: String,
    val headCount: Int,
    /** Date-only (`YYYY-MM-DD`). */
    val recordDate: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val clientId: String? = null,
)

@Serializable
data class CreateLivestockRequest(
    val animalType: String,
    val headCount: Int,
    val recordDate: String,
    val notes: String? = null,
    val clientId: String? = null,
)

@Serializable
data class UpdateLivestockRequest(
    val animalType: String? = null,
    val headCount: Int? = null,
    val recordDate: String? = null,
    val notes: String? = null,
)

/* ----------------------------------- sales -------------------------------- */

@Serializable
data class SaleDto(
    val transactionId: String,
    val farmerId: String,
    val item: String,
    val quantity: Double,
    val unitPrice: Double? = null,
    val amount: Double,
    val currency: String = "BWP",
    val buyerName: String? = null,
    /** Date-only (`YYYY-MM-DD`). */
    val date: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val clientId: String? = null,
)

@Serializable
data class CreateSaleRequest(
    val item: String,
    val quantity: Double,
    val unitPrice: Double? = null,
    val amount: Double,
    val currency: String? = null,
    val buyerName: String? = null,
    val date: String,
    val notes: String? = null,
    val clientId: String? = null,
)

@Serializable
data class UpdateSaleRequest(
    val item: String? = null,
    val quantity: Double? = null,
    val unitPrice: Double? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val buyerName: String? = null,
    val date: String? = null,
    val notes: String? = null,
)

/* --------------------------------- expenses ------------------------------- */

@Serializable
data class ExpenseDto(
    val expenseId: String,
    val farmerId: String,
    val category: String,
    val description: String? = null,
    val amount: Double,
    val currency: String = "BWP",
    /** Date-only (`YYYY-MM-DD`). */
    val expenseDate: String,
    val createdAt: String,
    val updatedAt: String,
    val clientId: String? = null,
)

@Serializable
data class CreateExpenseRequest(
    val category: String,
    val description: String? = null,
    val amount: Double,
    val currency: String? = null,
    val expenseDate: String,
    val clientId: String? = null,
)

@Serializable
data class UpdateExpenseRequest(
    val category: String? = null,
    val description: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val expenseDate: String? = null,
)

/* --------------------------------- summary -------------------------------- */

@Serializable
data class FinanceSummaryDto(
    val totalSales: Double,
    val totalExpenses: Double,
    val netBalance: Double,
    val currency: String = "BWP",
    val series: List<FinancePointDto> = emptyList(),
)

@Serializable
data class FinancePointDto(
    val period: String,
    val sales: Double,
    val expenses: Double,
)
