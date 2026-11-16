package bw.agriconnect.ui.auth

import bw.agriconnect.domain.model.AccountStatus

/**
 * The screens and words of account verification, kept as pure functions so the
 * honesty rules can be unit-tested without a device.
 *
 * The central rule: the app may only claim a message was sent when the server
 * told it so. `delivered == null` ("the server did not say") is a real third
 * state — it happens for an anonymous caller, or when the identifier matches no
 * account — and must never be rendered as "sent" or "failed".
 */
enum class VerifyStep {
    /** Reading `/auth/me`; nothing is known yet. */
    LOADING,

    /** The account is already verified; show that plainly and let the user leave. */
    ALREADY_VERIFIED,

    /** Ask for a code. */
    REQUEST_CODE,

    /** A code has been requested; collect it. */
    ENTER_CODE,

    /** The code was accepted. */
    VERIFIED,

    /** Suspended or deleted: verification would be misleading, so it is not offered. */
    UNAVAILABLE,
}

internal fun verifyStepFor(status: AccountStatus?): VerifyStep = when (status) {
    null -> VerifyStep.LOADING
    AccountStatus.VERIFIED -> VerifyStep.ALREADY_VERIFIED
    AccountStatus.REGISTERED, AccountStatus.UNVERIFIED -> VerifyStep.REQUEST_CODE
    else -> VerifyStep.UNAVAILABLE
}

/** Digits only, at most six — the server's code is exactly six digits. */
internal fun sanitiseCode(raw: String): String = raw.filter { it.isDigit() }.take(6)

internal fun isCodeComplete(code: String): Boolean = code.length == 6

internal fun resendLabel(secondsLeft: Int): String =
    if (secondsLeft > 0) "Send a new code in ${secondsLeft}s" else "Send a new code"

/**
 * The sentence shown after asking the server to send a code.
 *
 * `null` means the server did not report a transport outcome: say nothing about
 * delivery rather than guess. The Twilio-trial case is called out explicitly
 * because it is the most common reason a code silently fails to arrive, and the
 * fix (verifying the number in Twilio) is something the farmer or operator can
 * actually do.
 */
internal fun deliveryNotice(delivered: Boolean?, reason: String?, channel: String?): String? {
    if (delivered == null) return null
    val medium = if (channel == "EMAIL") "email" else "SMS"
    if (delivered) return "Your code was sent by $medium. It can take a minute to arrive."

    return when (reason) {
        "TRIAL_UNVERIFIED_NUMBER" ->
            "No $medium was sent. This server uses a Twilio trial account, and a trial can " +
                "only message numbers verified in Twilio. Add this number under Twilio's " +
                "Verified Caller IDs, or ask an administrator to upgrade the Twilio account."
        "NO_PROVIDER" ->
            "No $medium was sent because this server has no message provider configured. " +
                "An administrator must enable Twilio delivery."
        "PROVIDER_TIMEOUT", "PROVIDER_UNREACHABLE" ->
            "The message provider could not be reached, so nothing was sent. " +
                "Check your connection and try again."
        "RECIPIENT_OPTED_OUT" ->
            "This number has opted out of messages, so the $medium was not sent. " +
                "Contact support to re-enable messages."
        "INVALID_DESTINATION", "NOT_A_MOBILE_NUMBER" ->
            "This number cannot receive $medium messages. Update your phone number in " +
                "your profile, then try again."
        else ->
            "The message provider rejected the $medium, so nothing was sent. " +
                "Try again, or contact support if it keeps failing."
    }
}
