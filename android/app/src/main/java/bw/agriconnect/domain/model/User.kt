package bw.agriconnect.domain.model

/** A signed-in user. `role` is always the server's value — never client-chosen. */
data class User(
    val userId: String,
    val fullName: String,
    val phoneNumber: String,
    val email: String?,
    val role: UserRole,
    val accountStatus: AccountStatus,
    val avatarUrl: String?,
    val notificationPreferences: NotificationPreferences = NotificationPreferences(),
    val verifiedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val employerFarmerId: String? = null,
) {
    val initials: String
        get() = fullName.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "A" }
}

data class NotificationPreferences(
    val orders: Boolean = true,
    val sync: Boolean = true,
    val satellite: Boolean = true,
    val marketplace: Boolean = true,
)

/** Local identity of the session. Tokens live in encrypted storage, never here. */
data class Session(
    val userId: String,
    val role: UserRole,
    val fullName: String,
)

enum class DeletionRequestStatus { PENDING, PROCESSING, COMPLETED, CANCELLED }

data class DeletionRequest(
    val requestId: String,
    val status: DeletionRequestStatus,
    val requestedAt: String?,
    val effectiveAt: String?,
    val retention: List<RetentionItem> = emptyList(),
)

data class RetentionItem(
    val dataCategory: String,
    val justification: String,
    val retainUntil: String?,
)
