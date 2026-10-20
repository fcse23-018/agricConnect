package bw.agriconnect.data.update

import bw.agriconnect.data.remote.dto.VersionManifestDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the pure update decision rules. The server only publishes numbers; the
 * client alone decides whether a prompt is optional, required, or nothing at
 * all, so every defensive branch here is a promise that a broken or hostile
 * manifest can never trap the user in an unescapable prompt or offer an
 * insecure download.
 */
class UpdateDecisionTest {

    // --- Core rules -------------------------------------------------------

    @Test
    fun `below minSupported is Required`() {
        val source = manifest(min = 5, latest = 8)
        val decision = decide(currentVersionCode = 4, manifest = source)

        assertThat(decision).isInstanceOf(UpdateDecision.Required::class.java)
        // The decision carries the exact manifest it was handed - same object,
        // so the dialog shows the server's numbers and no copy is invented.
        assertThat(decision.manifestOrNull).isSameInstanceAs(source)
        assertThat(decision.reason).contains("installed=4")
    }

    @Test
    fun `at minSupported but below latest is Optional`() {
        val decision = decide(currentVersionCode = 5, manifest = manifest(min = 5, latest = 8))

        assertThat(decision).isInstanceOf(UpdateDecision.Optional::class.java)
        assertThat(decision.reason).contains("installed=5")
    }

    @Test
    fun `equal to latest is None`() {
        val decision = decide(currentVersionCode = 8, manifest = manifest(min = 1, latest = 8))

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
        assertThat(decision.manifestOrNull).isNull()
        assertThat(decision.reason).contains("is current")
    }

    @Test
    fun `newer than latest is None - downgrades are never offered`() {
        val decision = decide(currentVersionCode = 99, manifest = manifest(min = 1, latest = 8))

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    // --- Defensive rules --------------------------------------------------

    @Test
    fun `available false is None regardless of version numbers`() {
        val decision = decide(
            currentVersionCode = 1,
            manifest = manifest(min = 1, latest = 8).copy(
                available = false,
                reason = "Updates are disabled.",
            ),
        )

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
        // The server-provided reason is surfaced for diagnostics.
        assertThat(decision.reason).isEqualTo("Updates are disabled.")
    }

    @Test
    fun `zero latestVersionCode is None even when current is older`() {
        val decision = decide(
            currentVersionCode = 5,
            manifest = manifest(min = 1, latest = 0),
        )

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    @Test
    fun `negative latestVersionCode is None`() {
        val decision = decide(
            currentVersionCode = 5,
            manifest = manifest(min = 1, latest = -3),
        )

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    @Test
    fun `missing downloadUrl is None - never prompt for an install we cannot fetch`() {
        val decision = decide(
            currentVersionCode = 1,
            manifest = manifest(min = 1, latest = 8).copy(downloadUrl = null),
        )

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
        assertThat(decision.reason).contains("HTTPS")
    }

    @Test
    fun `blank downloadUrl is None`() {
        val decision = decide(
            currentVersionCode = 1,
            manifest = manifest(min = 1, latest = 8).copy(downloadUrl = "   "),
        )

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    @Test
    fun `http downloadUrl is None - downgrade must not bypass transport security`() {
        val decision = decide(
            currentVersionCode = 1,
            manifest = manifest(min = 1, latest = 8)
                .copy(downloadUrl = "http://example.com/app.apk"),
        )

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    @Test
    fun `uppercase HTTPS scheme is accepted - scheme check is case-insensitive`() {
        val decision = decide(
            currentVersionCode = 1,
            manifest = manifest(min = 2, latest = 8)
                .copy(downloadUrl = "HTTPS://example.com/app.apk"),
        )

        assertThat(decision).isInstanceOf(UpdateDecision.Required::class.java)
    }

    @Test
    fun `min above latest is clamped so newest build is never forced to update`() {
        // Hand-edited manifest: min (20) is above latest (10); the installed
        // build IS the latest, so the clamp must keep it usable.
        val decision = decide(currentVersionCode = 10, manifest = manifest(min = 20, latest = 10))

        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    @Test
    fun `min above latest still requires builds below the clamped min`() {
        val decision = decide(currentVersionCode = 4, manifest = manifest(min = 20, latest = 10))

        assertThat(decision).isInstanceOf(UpdateDecision.Required::class.java)
    }

    @Test
    fun `min below 1 is clamped up to 1`() {
        // Server default is 1; a nonsense 0 or negative min behaves the same.
        val atLatest = decide(currentVersionCode = 8, manifest = manifest(min = 0, latest = 8))
        val belowOne = decide(currentVersionCode = 8, manifest = manifest(min = -5, latest = 8))

        assertThat(atLatest).isInstanceOf(UpdateDecision.None::class.java)
        assertThat(belowOne).isInstanceOf(UpdateDecision.None::class.java)

        val currentOne = decide(currentVersionCode = 1, manifest = manifest(min = 0, latest = 8))
        assertThat(currentOne).isInstanceOf(UpdateDecision.Optional::class.java)
    }

    @Test
    fun `missing minSupported falls back to 1 like the backend default`() {
        val decision = decide(
            currentVersionCode = 1,
            manifest = manifest(min = null, latest = 8),
        )

        // current(1) >= fallback min(1) but < latest(8) => optional, not required.
        assertThat(decision).isInstanceOf(UpdateDecision.Optional::class.java)
    }

    @Test
    fun `manifestOrNull returns the manifest only for prompts`() {
        assertThat(
            decide(1, manifest(min = 2, latest = 8)).manifestOrNull,
        ).isNotNull()
        assertThat(
            decide(1, manifest(min = 1, latest = 8)).manifestOrNull,
        ).isNotNull()
        assertThat(
            decide(8, manifest(min = 1, latest = 8)).manifestOrNull,
        ).isNull()
    }

    private fun manifest(min: Int?, latest: Int) = VersionManifestDto(
        available = true,
        published = true,
        latestVersionName = "1.5.0",
        latestVersionCode = latest,
        minSupportedVersionCode = min,
        downloadUrl = "https://example.com/agriconnect.apk",
        packageName = "bw.agriconnect",
        releaseNotes = listOf("Fix irrigation scheduling."),
    )
}
