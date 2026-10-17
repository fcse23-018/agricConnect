package bw.agriconnect.domain.model

/**
 * Outcome of asking the backend to send a recovery or verification code.
 *
 * [message] is the server's acknowledgement (intentionally vague so it never
 * reveals whether an account exists). [devCode] is populated **only** by a
 * non-production backend running with `EXPOSE_DEV_CODES=true` and no real
 * SMS/email provider; the UI surfaces it so the recovery and verification
 * journeys can be completed end-to-end without a delivery provider. It is
 * always null against production.
 */
data class CodeDispatch(
    val message: String,
    val devCode: String?,
)
