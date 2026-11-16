package bw.agriconnect.ui.auth

import bw.agriconnect.domain.model.AccountStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The honesty rules of the verification screen, as plain functions.
 *
 * The important case is `deliveryNotice(null, ...)`: the server did not report a
 * transport outcome, so the app must say nothing about delivery. Collapsing that
 * into "sent" would tell a farmer to wait for an SMS that was never requested;
 * collapsing it into "failed" would blame the network for an unknown.
 */
class VerificationRulesTest {

    @Test
    fun `unknown status is loading, never a guess`() {
        assertThat(verifyStepFor(null)).isEqualTo(VerifyStep.LOADING)
    }

    @Test
    fun `verified accounts are shown as already verified`() {
        assertThat(verifyStepFor(AccountStatus.VERIFIED)).isEqualTo(VerifyStep.ALREADY_VERIFIED)
    }

    @Test
    fun `registered and unverified accounts can request a code`() {
        assertThat(verifyStepFor(AccountStatus.REGISTERED)).isEqualTo(VerifyStep.REQUEST_CODE)
        assertThat(verifyStepFor(AccountStatus.UNVERIFIED)).isEqualTo(VerifyStep.REQUEST_CODE)
    }

    @Test
    fun `suspended and deleted accounts are not offered verification`() {
        assertThat(verifyStepFor(AccountStatus.SUSPENDED)).isEqualTo(VerifyStep.UNAVAILABLE)
        assertThat(verifyStepFor(AccountStatus.PENDING_DELETION)).isEqualTo(VerifyStep.UNAVAILABLE)
        assertThat(verifyStepFor(AccountStatus.DELETED)).isEqualTo(VerifyStep.UNAVAILABLE)
    }

    @Test
    fun `code input keeps digits only and caps at six`() {
        assertThat(sanitiseCode(" 1a2-3 4\n5 6 ")).isEqualTo("123456")
        assertThat(sanitiseCode("1234567890")).isEqualTo("123456")
        assertThat(sanitiseCode("abc")).isEmpty()
    }

    @Test
    fun `only six digits can be submitted`() {
        assertThat(isCodeComplete("12345")).isFalse()
        assertThat(isCodeComplete("123456")).isTrue()
        assertThat(isCodeComplete("1234567")).isFalse()
    }

    @Test
    fun `resend label counts down then invites a new send`() {
        assertThat(resendLabel(45)).isEqualTo("Send a new code in 45s")
        assertThat(resendLabel(1)).isEqualTo("Send a new code in 1s")
        assertThat(resendLabel(0)).isEqualTo("Send a new code")
        assertThat(resendLabel(-3)).isEqualTo("Send a new code")
    }

    @Test
    fun `an unreported transport outcome makes no delivery claim`() {
        assertThat(deliveryNotice(delivered = null, reason = null, channel = "SMS")).isNull()
        assertThat(deliveryNotice(delivered = null, reason = "TRIAL_UNVERIFIED_NUMBER", channel = "SMS")).isNull()
    }

    @Test
    fun `a delivered message is stated as sent`() {
        val notice = deliveryNotice(delivered = true, reason = null, channel = "SMS")
        assertThat(notice).contains("sent by SMS")
        assertThat(deliveryNotice(delivered = true, reason = null, channel = "EMAIL")).contains("sent by email")
    }

    @Test
    fun `the Twilio trial refusal explains exactly what to do`() {
        val notice = deliveryNotice(delivered = false, reason = "TRIAL_UNVERIFIED_NUMBER", channel = "SMS")
        assertThat(notice).contains("No SMS was sent")
        assertThat(notice).contains("Twilio trial")
        assertThat(notice).contains("Verified Caller IDs")
    }

    @Test
    fun `a missing provider is named as a server problem`() {
        val notice = deliveryNotice(delivered = false, reason = "NO_PROVIDER", channel = "SMS")
        assertThat(notice).contains("no message provider configured")
        assertThat(notice).contains("administrator")
    }

    @Test
    fun `unreachable and timed-out providers are retryable, not blamed on the user`() {
        assertThat(deliveryNotice(false, "PROVIDER_TIMEOUT", "SMS")).contains("could not be reached")
        assertThat(deliveryNotice(false, "PROVIDER_UNREACHABLE", "SMS")).contains("could not be reached")
    }

    @Test
    fun `an unknown refusal never claims success`() {
        val notice = deliveryNotice(delivered = false, reason = "SOMETHING_NEW", channel = "SMS")
        assertThat(notice).contains("nothing was sent")
        assertThat(notice).doesNotContain("was sent by")
    }
}
