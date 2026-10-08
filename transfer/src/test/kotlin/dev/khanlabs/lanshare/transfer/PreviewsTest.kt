package dev.khanlabs.lanshare.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewsTest {
    @Test
    fun photosAlwaysGetAPicture() {
        for (sdk in listOf(23, 28, 29, 34)) {
            assertTrue(Previews.isPreviewMime("image/jpeg", sdk), "jpeg on $sdk")
            assertTrue(Previews.isPreviewMime("image/heic", sdk), "heic on $sdk")
        }
    }

    @Test
    fun videosGetOneFromAndroid10BecauseThatIsWhereTheSystemMakesIt() {
        assertFalse(Previews.isPreviewMime("video/mp4", 28))
        assertTrue(Previews.isPreviewMime("video/mp4", 29))
        assertTrue(Previews.isPreviewMime("video/x-matroska", 34))
    }

    @Test
    fun otherFilesGetNone() {
        for (mime in listOf("application/pdf", "text/plain", "application/octet-stream", "audio/mpeg", "application/zip")) {
            assertFalse(Previews.isPreviewMime(mime, 34), mime)
        }
    }

    @Test
    fun aCardShowsAtMostFourPictures() {
        assertEquals(4, Previews.MAX)
    }
}
