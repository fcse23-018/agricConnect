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
    /**
     * What the transport did, as reported by the server **only** to the signed-in
     * owner of the account. `null` means the server did not say (an anonymous
     * request, or an identifier that matches no account) and must never be
     * rendered as "sent" or "failed".
     */
    val delivered: Boolean? = null,
    /** `SMS` | `EMAIL`; null when the server did not report a channel. */
    val channel: String? = null,
    /**
     * Fixed reason code when [delivered] is false, e.g.
     * `TRIAL_UNVERIFIED_NUMBER` (Twilio 21608) or `NO_PROVIDER`.
     */
    val undeliveredReason: String? = null,
    /** How long the code stays valid. Server-supplied; 10 min is the documented default. */
    val expiresInMinutes: Int = 10,
)
