package bw.agriconnect.data.update

/**
 * An update failure whose [message] is safe and actionable to show the user.
 *
 * Thrown by the download / verify / install pipeline when a check *did* find
 * an update but performing it failed (network drop, no storage, checksum or
 * signature mismatch, package mismatch, ...). Cancellation is never wrapped in
 * this — `CancellationException` propagates untouched so a user-initiated
 * Cancel reads as "stop", not as an error.
 */
class UpdateException(message: String) : Exception(message)
