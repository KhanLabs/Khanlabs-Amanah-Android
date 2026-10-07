package dev.khanlabs.lanshare.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveFolderTest {
    @Test
    fun aFreeNameIsKept() {
        assertEquals("a.txt", SaveFolder.freeName("a.txt") { false })
    }

    @Test
    fun aTakenNameGetsTheNextFreeNumber() {
        val taken = setOf("a.txt", "a (1).txt", "b.txt")
        assertEquals("a (2).txt", SaveFolder.freeName("a.txt", taken::contains))
        assertEquals("b (1).txt", SaveFolder.freeName("b.txt", taken::contains))
        assertEquals("noext (1)", SaveFolder.freeName("noext", setOf("noext")::contains))
    }

    @Test
    fun theCheckGetsLowerCaseBecauseSharedStorageIgnoresCase() {
        val seen = ArrayList<String>()
        SaveFolder.freeName("Photo.JPG") { seen += it; seen.size < 2 }
        assertEquals(listOf("photo.jpg", "photo (1).jpg"), seen)
        assertEquals("Photo (1).JPG", SaveFolder.freeName("Photo.JPG", setOf("photo.jpg")::contains))
    }

    @Test
    fun aPartialNameIsHiddenAndRecognised() {
        val partial = SaveFolder.partialName("movie.mp4", "ab12cd34")
        assertEquals(".movie.mp4.ab12cd34.uploading", partial)
        assertTrue(FolderStore.isPartial(partial))
    }

    @Test
    fun readsTheVolumeAndPathOfAPhoneStorageFolder() {
        assertEquals("primary", SaveFolder.volume(SaveFolder.EXTERNAL_STORAGE, "primary:Documents/Amanah"))
        assertEquals("Documents/Amanah", SaveFolder.path(SaveFolder.EXTERNAL_STORAGE, "primary:Documents/Amanah"))
        assertEquals("", SaveFolder.path(SaveFolder.EXTERNAL_STORAGE, "primary:"))
        assertEquals("1A2B-3C4D", SaveFolder.volume(SaveFolder.EXTERNAL_STORAGE, "1A2B-3C4D:Photos"))
    }

    @Test
    fun anotherProviderHasNoVolumeOrPath() {
        assertNull(SaveFolder.volume("com.google.android.apps.docs.storage", "abc123"))
        assertNull(SaveFolder.path("com.google.android.apps.docs.storage", "abc:123"))
        assertNull(SaveFolder.volume(SaveFolder.EXTERNAL_STORAGE, "no-colon"))
    }

    @Test
    fun theSettingsWordsForAFolder() {
        val ext = SaveFolder.EXTERNAL_STORAGE
        assertEquals("Documents/Amanah", SaveFolder.label(ext, "primary:Documents/Amanah", "Amanah"))
        assertEquals("Internal storage", SaveFolder.label(ext, "primary:", "Internal storage"))
        assertEquals("Storage 1A2B-3C4D: Photos/2026", SaveFolder.label(ext, "1A2B-3C4D:Photos/2026", "2026"))
        assertEquals("Storage 1A2B-3C4D", SaveFolder.label(ext, "1A2B-3C4D:", null))
        assertEquals("Received", SaveFolder.label("com.example.cloud", "x9", "Received"))
        assertEquals("the chosen folder", SaveFolder.label("com.example.cloud", "x9", null))
    }
}
