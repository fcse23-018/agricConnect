package bw.agriconnect.ui.satellite

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The status word on the latest-observation card is the app's promise about whether a
 * reading is live. Getting the precedence wrong is not a cosmetic bug: calling a restored
 * cache "Updated" would tell a farmer that a satellite passed over their plot today when
 * the newest clear image may be weeks old.
 *
 * These tests pin every branch and the order between them.
 */
class ObservationStateLabelTest {

    private fun label(
        isLoading: Boolean = false,
        hasError: Boolean = false,
        isOffline: Boolean = false,
        fromCache: Boolean = false,
        hasReading: Boolean = true,
    ) = observationStateLabel(isLoading, hasError, isOffline, fromCache, hasReading)

    @Test
    fun `a network payload with a reading is Updated`() {
        assertThat(label()).isEqualTo("Updated")
    }

    @Test
    fun `a payload restored from the device mirror is Cached`() {
        assertThat(label(fromCache = true)).isEqualTo("Cached")
    }

    @Test
    fun `cached outranks a failed refresh`() {
        // The retry failed but real stored data is on screen: that is Cached, not
        // Update failed, and certainly not Updated.
        assertThat(label(fromCache = true, hasError = true, isOffline = true)).isEqualTo("Cached")
        assertThat(label(fromCache = true, hasError = true)).isEqualTo("Cached")
    }

    @Test
    fun `a connectivity failure with nothing stored is Offline`() {
        assertThat(label(hasError = true, isOffline = true, hasReading = false)).isEqualTo("Offline")
    }

    @Test
    fun `a non-connectivity failure is Update failed`() {
        assertThat(label(hasError = true, isOffline = false, hasReading = false))
            .isEqualTo("Update failed")
    }

    @Test
    fun `an answered request with no usable pass says so instead of inventing one`() {
        assertThat(label(hasReading = false)).isEqualTo("No observation available")
    }

    @Test
    fun `a refresh in flight is never shown as settled`() {
        assertThat(label(isLoading = true)).isEqualTo("Checking for a new pass")
        assertThat(label(isLoading = true, fromCache = true)).isEqualTo("Checking for a new pass")
        assertThat(label(isLoading = true, hasError = true)).isEqualTo("Checking for a new pass")
    }

    @Test
    fun `only a successful network payload is ever labelled Updated`() {
        // Over every combination of the inputs, Updated must imply all four preconditions.
        // `isOffline` is deliberately not one of them: it is derived from `fromCache` at the
        // call site, so on its own it cannot make a fresh network payload stale.
        val combinations = listOf(false, true)
        var updated = 0
        for (loading in combinations) {
            for (error in combinations) {
                for (offline in combinations) {
                    for (cache in combinations) {
                        for (reading in combinations) {
                            val l = observationStateLabel(loading, error, offline, cache, reading)
                            if (l == "Updated") {
                                updated++
                                assertThat(loading).isFalse()
                                assertThat(error).isFalse()
                                assertThat(cache).isFalse()
                                assertThat(reading).isTrue()
                            }
                        }
                    }
                }
            }
        }
        // Reachable: (loading=false, error=false, cache=false, reading=true) with either
        // value of `offline`. Anything else must have produced a different word.
        assertThat(updated).isEqualTo(2)
    }
}
