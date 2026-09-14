package dev.sidecar.engine

/** A configured folder, as shown in the folder list. */
data class FolderInfo(
    val id: String,
    val label: String,
    val path: String,
    val isSelective: Boolean,
    val isPaused: Boolean,
    val connectedPeers: Int,
)

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
) {
    /** Listed in the index but not stored locally -- the on-demand case. */
    val isRemoteOnly: Boolean get() = !isDirectory && !isLocallyPresent
}
