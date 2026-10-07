package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.Names

/** The plain rules behind a save folder the person picked (no Android here, so they can be tested). */
object SaveFolder {
    /** The authority of the phone's own storage provider; its document IDs read "primary:Documents/Amanah". */
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * [name], or "name (1).ext", "name (2).ext" and so on: the first one that [isTaken] says is free. [isTaken] gets the
     * name in lower case, because the phone's shared storage does not tell "A.txt" from "a.txt".
     */
    fun freeName(name: String, isTaken: (String) -> Boolean): String {
        var n = 0
        while (true) {
            val candidate = Names.numbered(name, n++)
            if (!isTaken(candidate.lowercase())) return candidate
        }
    }

    /** The name a file is written under until it is whole (hidden, like [FolderStore]'s). */
    fun partialName(name: String, random: String) = ".$name.$random${FolderStore.PARTIAL_SUFFIX}"

    /**
     * The volume in an external-storage document ID ("primary" or an SD card's ID), or null for another provider,
     * whose IDs mean nothing outside it.
     */
    fun volume(authority: String, docId: String): String? =
        if (authority == EXTERNAL_STORAGE && ':' in docId) docId.substringBefore(':') else null

    /** The folders inside the volume, "Documents/Amanah", or "" for the volume's top. Null for another provider. */
    fun path(authority: String, docId: String): String? =
        if (volume(authority, docId) == null) null else docId.substringAfter(':').trim('/')

    /**
     * Words for the folder in Settings: "Documents/Amanah" on the phone's storage, "Storage 1A2B-3C4D: Photos" on a
     * card or USB drive, and the folder's own name for a provider such as a cloud drive.
     */
    fun label(authority: String, docId: String, displayName: String?): String {
        val volume = volume(authority, docId) ?: return displayName?.takeIf { it.isNotEmpty() } ?: "the chosen folder"
        val path = path(authority, docId).orEmpty()
        return when {
            volume == "primary" -> path.ifEmpty { "Internal storage" }
            path.isEmpty() -> "Storage $volume"
            else -> "Storage $volume: $path"
        }
    }
}
