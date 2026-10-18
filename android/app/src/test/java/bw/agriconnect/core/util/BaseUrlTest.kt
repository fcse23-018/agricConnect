package bw.agriconnect.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A saved server override is typed by hand on a phone keyboard, so the rules that decide
 * whether it is usable are worth pinning: Retrofit needs a trailing `/`, and a value that
 * is not absolute `http(s)` must be rejected rather than persisted and handed to OkHttp.
 */
class BaseUrlTest {

    @Test
    fun addsTrailingSlash_whenMissing() {
        assertThat(BaseUrl.normalize("https://api.agriconnect.bw/api/v1"))
            .isEqualTo("https://api.agriconnect.bw/api/v1/")
    }

    @Test
    fun keepsTrailingSlash_thatIsAlreadyThere() {
        assertThat(BaseUrl.normalize("http://192.168.1.5:4000/api/v1/"))
            .isEqualTo("http://192.168.1.5:4000/api/v1/")
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertThat(BaseUrl.normalize("  https://x-4000.app.github.dev/api/v1/  "))
            .isEqualTo("https://x-4000.app.github.dev/api/v1/")
    }

    @Test
    fun hostWithoutAPath_isAccepted() {
        assertThat(BaseUrl.normalize("https://api.agriconnect.bw"))
            .isEqualTo("https://api.agriconnect.bw/")
    }

    @Test
    fun rejectsNullBlankAndRelativeValues() {
        assertThat(BaseUrl.normalize(null)).isNull()
        assertThat(BaseUrl.normalize("")).isNull()
        assertThat(BaseUrl.normalize("   ")).isNull()
        assertThat(BaseUrl.normalize("/api/v1/")).isNull()
        assertThat(BaseUrl.normalize("api.agriconnect.bw")).isNull()
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertThat(BaseUrl.normalize("ftp://files.example.com/")).isNull()
        assertThat(BaseUrl.normalize("javascript:alert(1)")).isNull()
    }
}
