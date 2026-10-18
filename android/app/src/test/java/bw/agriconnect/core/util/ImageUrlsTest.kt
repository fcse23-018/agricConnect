package bw.agriconnect.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The relative-to-absolute image resolution is the difference between a photo that
 * renders and a 404: the backend stores origin-relative paths (`/uploads/…`) while the
 * HTTP client is pinned to the versioned API root (`…/api/v1/`). These cases pin the
 * rules.
 */
class ImageUrlsTest {

    @Test
    fun relativeUploadPath_resolvesAgainstOrigin() {
        assertThat(ImageUrls.resolve("/uploads/pic.jpg", "http://10.0.2.2:4000"))
            .isEqualTo("http://10.0.2.2:4000/uploads/pic.jpg")
    }

    @Test
    fun relativePath_withoutLeadingSlash_stillResolves() {
        assertThat(ImageUrls.resolve("uploads/pic.jpg", "https://api.agriconnect.bw"))
            .isEqualTo("https://api.agriconnect.bw/uploads/pic.jpg")
    }

    @Test
    fun absoluteUrl_isReturnedUnchanged() {
        val url = "https://cdn.example.com/a.jpg"
        assertThat(ImageUrls.resolve(url, "http://localhost:4000")).isEqualTo(url)
    }

    @Test
    fun deviceLocalUri_isReturnedUnchanged() {
        // A just-picked image must preview before it has been uploaded.
        val uri = "content://media/external/images/media/42"
        assertThat(ImageUrls.resolve(uri, "http://localhost:4000")).isEqualTo(uri)
    }

    @Test
    fun blankOrNull_isNull() {
        assertThat(ImageUrls.resolve(null, "http://localhost:4000")).isNull()
        assertThat(ImageUrls.resolve("   ", "http://localhost:4000")).isNull()
    }

    @Test
    fun missingOrigin_leavesRelativePathUntouched() {
        assertThat(ImageUrls.resolve("/uploads/pic.jpg", null)).isEqualTo("/uploads/pic.jpg")
    }

    @Test
    fun originOf_dropsDefaultPort_butKeepsAnExplicitOne() {
        assertThat(ImageUrls.originOf("https://api.agriconnect.bw/api/v1/"))
            .isEqualTo("https://api.agriconnect.bw")
        assertThat(ImageUrls.originOf("http://10.0.2.2:4000/api/v1/"))
            .isEqualTo("http://10.0.2.2:4000")
    }
}
