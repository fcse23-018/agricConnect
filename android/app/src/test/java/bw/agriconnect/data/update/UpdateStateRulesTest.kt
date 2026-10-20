package bw.agriconnect.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the throttle window and the "Update later" deduplication rules, plus the
 * checksum helpers. These are the only state the updater keeps between checks,
 * and both exist to protect the user: the throttle keeps the app from polling a
 * public endpoint on every resume, and the dismissed code keeps "Later" from
 * nagging about the same release.
 */
class UpdateStateRulesTest {

    private val sixHours = 6L * 60 * 60 * 1000

    // --- isCheckDue -------------------------------------------------------

    @Test
    fun `never checked is always due`() {
        assertThat(isCheckDue(lastCheckAtMillis = 0L, nowMillis = ANY_NOW, intervalMillis = sixHours))
            .isTrue()
    }

    @Test
    fun `checked just now is not due`() {
        val now = ANY_NOW
        assertThat(isCheckDue(lastCheckAtMillis = now, nowMillis = now, intervalMillis = sixHours))
            .isFalse()
    }

    @Test
    fun `checked one millisecond before the window closes is not due`() {
        val now = ANY_NOW
        assertThat(
            isCheckDue(
                lastCheckAtMillis = now - sixHours + 1,
                nowMillis = now,
                intervalMillis = sixHours,
            ),
        ).isFalse()
    }

    @Test
    fun `checked exactly at the window boundary is due`() {
        val now = ANY_NOW
        assertThat(
            isCheckDue(lastCheckAtMillis = now - sixHours, nowMillis = now, intervalMillis = sixHours),
        ).isTrue()
    }

    @Test
    fun `checked long ago is due`() {
        val now = ANY_NOW
        assertThat(
            isCheckDue(lastCheckAtMillis = now - (72 * 60 * 60 * 1000), nowMillis = now, intervalMillis = sixHours),
        ).isTrue()
    }

    @Test
    fun `a clock that moved backwards does not re-trigger a check`() {
        // Device clock correction: lastCheck is "in the future". The negative
        // delta must not be read as "due", which would storm the endpoint.
        val now = ANY_NOW
        assertThat(isCheckDue(lastCheckAtMillis = now + sixHours, nowMillis = now, intervalMillis = sixHours))
            .isFalse()
    }

    // --- shouldPromptFor --------------------------------------------------

    @Test
    fun `nothing dismissed yet always prompts`() {
        assertThat(shouldPromptFor(dismissedVersionCode = null, latestVersionCode = 9)).isTrue()
    }

    @Test
    fun `the same version the user deferred does not prompt again`() {
        assertThat(shouldPromptFor(dismissedVersionCode = 9, latestVersionCode = 9)).isFalse()
    }

    @Test
    fun `a strictly newer release prompts again after a deferral`() {
        assertThat(shouldPromptFor(dismissedVersionCode = 9, latestVersionCode = 10)).isTrue()
    }

    @Test
    fun `a lower release code than the deferral does not prompt`() {
        // Should not happen server-side, but a hand-edited manifest must not
        // resurrect a prompt the user already declined.
        assertThat(shouldPromptFor(dismissedVersionCode = 9, latestVersionCode = 8)).isFalse()
    }

    @Test
    fun `dismissing an older code still prompts for the newest`() {
        assertThat(shouldPromptFor(dismissedVersionCode = 3, latestVersionCode = 15)).isTrue()
    }

    // --- sha256Hex / toHexLower -------------------------------------------

    @Test
    fun `sha256Hex matches the known digest of the empty input`() {
        assertThat(sha256Hex(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `sha256Hex matches the known digest of abc`() {
        assertThat(sha256Hex("abc".toByteArray(Charsets.UTF_8)))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `sha256Hex is lower-case hex of fixed length`() {
        val digest = sha256Hex("AgriConnect".toByteArray(Charsets.UTF_8))

        assertThat(digest).hasLength(64)
        assertThat(digest).matches("[0-9a-f]{64}")
    }

    @Test
    fun `toHexLower pads single-digit nibbles`() {
        assertThat(byteArrayOf(0x00, 0x0F, 0x10, 0xFF.toByte()).toHexLower()).isEqualTo("000f10ff")
        assertThat(byteArrayOf(0x00, 0x0F, 0x10).toHexLower()).isEqualTo("000f10")
    }

    @Test
    fun `toHexLower processes high bytes without sign extension`() {
        assertThat(byteArrayOf(0x80.toByte(), 0xAB.toByte()).toHexLower()).isEqualTo("80ab")
    }

    @Test
    fun `toHexLower of an empty array is empty`() {
        assertThat(ByteArray(0).toHexLower()).isEmpty()
    }

    // --- checksumMatches --------------------------------------------------

    @Test
    fun `absent checksum is not a failure - other checks still apply`() {
        assertThat(checksumMatches(expectedHex = null, actualHex = "abc123")).isTrue()
        assertThat(checksumMatches(expectedHex = "", actualHex = "abc123")).isTrue()
        assertThat(checksumMatches(expectedHex = "   ", actualHex = "abc123")).isTrue()
    }

    @Test
    fun `identical checksums match`() {
        val digest = sha256Hex("release".toByteArray(Charsets.UTF_8))
        assertThat(checksumMatches(expectedHex = digest, actualHex = digest)).isTrue()
    }

    @Test
    fun `checksum comparison is case-insensitive and trims whitespace`() {
        assertThat(checksumMatches(expectedHex = "ABCDEF", actualHex = "abcdef")).isTrue()
        assertThat(checksumMatches(expectedHex = "  abcdef  ", actualHex = "abcdef")).isTrue()
        assertThat(checksumMatches(expectedHex = "ABCDEF", actualHex = "abcdef".uppercase())).isTrue()
    }

    @Test
    fun `a different checksum does not match`() {
        assertThat(checksumMatches(expectedHex = "abcdef", actualHex = "abcde0")).isFalse()
    }

    @Test
    fun `a truncated checksum does not match`() {
        val digest = sha256Hex("release".toByteArray(Charsets.UTF_8))
        assertThat(checksumMatches(expectedHex = digest.take(32), actualHex = digest)).isFalse()
    }

    private companion object {
        /** Arbitrary fixed epoch millis; only deltas matter. */
        const val ANY_NOW = 1_760_000_000_000L
    }
}
