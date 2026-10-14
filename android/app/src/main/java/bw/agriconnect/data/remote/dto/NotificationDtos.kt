package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire types for the notification feed and push-token registration
 * (docs/02-API-CONTRACT.md §2.9).
 */

@Serializable
data class NotificationDto(
    val notificationId: String,
    /** Not carried by the domain model (the feed is always the caller's own). */
    val userId: String? = null,
    val type: String,
    val title: String,
    val body: String,
    val relatedEntityType: String? = null,
    val relatedEntityId: String? = null,
    val isRead: Boolean = false,
    val createdAt: String,
)

@Serializable
data class DeviceTokenRequest(
    val token: String,
    /** Defaults to the only platform this client targets. */
    val platform: String = "android",
)
