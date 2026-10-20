package bw.agriconnect.data.update

import java.security.MessageDigest

/**
 * Small hashing helpers shared by the download and verification steps.
 * Pure JVM code so the checksum behaviour is unit-testable without Android.
 */

/** SHA-256 of [bytes] as lower-case hex. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()

/** Lower-case hex encoding; locale-independent by construction. */
fun ByteArray.toHexLower(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4])
        out.append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}

/**
 * True when the observed SHA-256 [actualHex] matches the manifest's
 * [expectedHex], or when the manifest published no checksum at all
 * (null / blank) — an absent checksum is not a failure, the package,
 * version and signature checks below still apply.
 *
 * Comparison is case-insensitive and trims surrounding whitespace so a
 * hand-edited manifest with upper-case hex or a stray space still verifies.
 */
fun checksumMatches(expectedHex: String?, actualHex: String): Boolean =
    expectedHex.isNullOrBlank() ||
        expectedHex.trim().equals(actualHex, ignoreCase = true)

private const val HEX_DIGITS = "0123456789abcdef"
