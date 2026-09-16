package ch.steigis.dinghy.engine

/** A configured folder, as shown in the folder list. */
data class FolderInfo(
    val id: String,
    val label: String,
    val path: String,
    val isSelective: Boolean,
    /**
     * The folder is the device's photo library rather than files on disk. Its
     * contents are read from MediaStore on demand, it is send-only, and it has
     * no path that anything else can write to.
     */
    val isPhotoFolder: Boolean = false,
    val isPaused: Boolean,
    val connectedPeers: Int,
    /** Everything the folder knows about, downloaded or not. */
    val globalFiles: Long,
    val globalBytes: Long,
    /** What this device actually stores -- the number that matters on a phone. */
    val localBytes: Long,
)

/**
 * A device that announced itself on the local network but is not configured as
 * a peer. Discovery is the whole reason for the multicast lock: without it,
 * pairing means typing a 63-character id by hand.
 */
data class DiscoveredDevice(
    val deviceId: String,
    val addresses: List<String>,
) {
    /** Discovery carries no name, so the leading block of the id stands in. */
    val displayName: String get() = deviceId.substringBefore('-')
}

/**
 * A configured peer, as shown in the device list.
 *
 * [name] is what the other device calls itself, which Syncthing only learns
 * once the two have connected; before that it is empty and the id stands in.
 */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val isConnected: Boolean,
    val isPaused: Boolean,
    val addresses: List<String>,
    /** Epoch millis, or null if the two have never connected. */
    val lastSeen: Long?,
) {
    /** Never blank: an unnamed peer is shown by the leading block of its id. */
    val displayName: String
        get() = name.ifBlank { deviceId.substringBefore('-') }
}

/**
 * One entry in the *global* index. It exists whether or not its content has
 * been downloaded, which is what makes browsing-without-syncing possible.
 */
data class EntryInfo(
    val name: String,
    /** Path relative to the folder root, without a leading slash. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    /** The file's content is on this device. */
    val isLocallyPresent: Boolean,
    /** The user pinned this entry specifically. */
    val isExplicitlySelected: Boolean,
    /** Selected, whether explicitly or because an ancestor was pinned. */
    val isSelected: Boolean,
    /** A Syncthing conflict copy, e.g. "notes.sync-conflict-20260101-120000-ABCDEFG.md". */
    val isConflictCopy: Boolean = false,
    /**
     * When the file was last changed, by whoever changed it, in epoch millis.
     * Null when the index has no timestamp -- directories, and entries the
     * engine could not read a date from.
     *
     * This is the file's own modification time as it travelled through the
     * index, not when this device fetched it. On a phone holding a file list
     * rather than the files, that is the more useful of the two: it says how
     * current the thing you are looking at is, whether or not you have it.
     */
    val modifiedAt: Long? = null,
    /** Short id of the device that last changed it; empty when unknown. */
    val modifiedBy: String = "",
    /**
     * Folder this entry belongs to. Only filled in by search, and only there
     * because a search across every folder returns paths that are relative to
     * their own folder root -- without this, two files called notes.md in
     * different folders are indistinguishable. Browsing already knows which
     * folder it is in, so it leaves these empty.
     */
    val folderId: String = "",
    val folderLabel: String = "",
) {
    /** Listed in the index but not stored locally -- the on-demand case. */
    val isRemoteOnly: Boolean get() = !isDirectory && !isLocallyPresent
}
