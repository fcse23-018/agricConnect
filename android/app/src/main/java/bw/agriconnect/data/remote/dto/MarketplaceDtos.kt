package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire types for the marketplace and buyer orders (docs/02-API-CONTRACT.md §2.7
 * and §2.8).
 */

/**
 * The generic pagination envelope used across the API:
 * `{ items, page, pageSize, total, hasMore }`.
 *
 * Making it generic (rather than one class per entity) keeps pagination handling
 * in the layer that owns it and avoids a near-identical wrapper per endpoint.
 */
@Serializable
data class PageDto<T>(
    val items: List<T> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class ListingDto(
    val listingId: String,
    val farmerId: String,
    val produce: String,
    val description: String? = null,
    val quantity: Double,
    val reservedQty: Double = 0.0,
    val soldQty: Double = 0.0,
    /** Convenience value computed server-side; the domain model derives it too. */
    val availableQty: Double? = null,
    /** `kg` | `tonne` | `bag` | `crate` | `bunch` | `head` | `litre`. */
    val unit: String = "kg",
    val price: Double,
    val currency: String = "BWP",
    /** `DRAFT` | `PENDING_PUBLISH` | `SYNCING` | `RETRY` | `PUBLISHED` | `SOLD`. */
    val status: String,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val publishedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val farmerName: String? = null,
)

@Serializable
data class CreateListingRequest(
    val produce: String,
    val description: String? = null,
    val quantity: Double,
    val unit: String? = null,
    val price: Double,
    val currency: String? = null,
    val imageUrl: String? = null,
    val locationText: String? = null,
)

@Serializable
data class UpdateListingRequest(
    val produce: String? = null,
    val description: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val imageUrl: String? = null,
    val locationText: String? = null,
)

@Serializable
data class OrderDto(
    val orderId: String,
    val listingId: String,
    val buyerId: String,
    val farmerId: String,
    val quantity: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val currency: String = "BWP",
    /** `PENDING` | `CONFIRMED` | `COMPLETED` | `CANCELLED`. */
    val status: String,
    val note: String? = null,
    val buyerContact: String? = null,
    val farmerDecisionAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val produce: String? = null,
    val buyerName: String? = null,
)

@Serializable
data class CreateOrderRequest(
    val listingId: String,
    val quantity: Double,
    val note: String? = null,
)
