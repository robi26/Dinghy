package ch.steigis.dinghy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.R
import ch.steigis.dinghy.engine.EntryInfo
import ch.steigis.dinghy.engine.SyncEngine
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browses one directory level of a folder's global index.
 *
 * Every entry here exists in the index whether or not its content is on the
 * device. The checkbox is the pin: ticking it writes an exception into
 * .stignore and the file is fetched.
 *
 * [grid] lays the same entries out as thumbnails instead of rows, which is the
 * only way a folder of photos is browsable by eye. The choice belongs to the
 * app bar that toggles it, so it arrives as a parameter.
 */
@Composable
fun BrowserScreen(
    folderId: String,
    prefix: String,
    grid: Boolean = false,
    onOpenDirectory: (String) -> Unit,
) {
    var entries by remember(folderId, prefix) { mutableStateOf<List<EntryInfo>?>(null) }
    var error by remember(folderId, prefix) { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }
    var sheetFor by remember { mutableStateOf<EntryInfo?>(null) }
    // A photo folder is the library seen through Syncthing: there is nothing
    // to pin (it is all here already) and nothing to download.
    var photoFolder by remember(folderId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderId) { photoFolder = SyncEngine.isPhotoFolder(folderId) }

    sheetFor?.let { selected ->
        FileSheet(
            folderId = folderId,
            entry = selected,
            onDismiss = { sheetFor = null; reloadToken++ },
        )
    }

    LaunchedEffect(folderId, prefix, reloadToken) {
        error = null
        entries = try {
            SyncEngine.browse(folderId, prefix)
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            emptyList()
        }
    }

    // A pinned file arrives asynchronously, so the list has to be re-read until
    // it lands -- otherwise a file the user just asked for keeps claiming it is
    // not downloaded. Polling stops as soon as nothing is outstanding.
    val pending = entries.orEmpty().any { it.isSelected && it.isRemoteOnly }
    LaunchedEffect(folderId, prefix, pending) {
        while (pending) {
            delay(1500)
            entries = runCatching { SyncEngine.browse(folderId, prefix) }.getOrDefault(entries)
        }
    }

    val current = entries

    // How many entries each directory row holds. Counted here rather than in
    // browse() because it is one index query per directory: folding it into the
    // listing would make opening a directory wait for a count of every
    // subdirectory in it, to show a number that is only a subtitle. Keyed on
    // the paths rather than on the entries, so pinning something does not
    // recount a directory whose contents did not change.
    val directoryPaths = current.orEmpty().filter { it.isDirectory }.map { it.path }
    var childCounts by remember(folderId, prefix) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(folderId, directoryPaths) {
        // Assigned once at the end rather than per directory: the subtitles
        // settle together instead of appearing one by one down the list.
        val counts = mutableMapOf<String, Int>()
        directoryPaths.forEach { path ->
            runCatching { SyncEngine.childCount(folderId, path) }
                .onSuccess { counts[path] = it }
        }
        childCounts = counts
    }

    when {
        error != null -> Text(
            error!!,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )

        current == null -> Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }

        current.isEmpty() -> Text(
            "Nothing here yet. If this folder was just added, the index is still arriving.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        else -> {
            // Tapping and pinning mean the same thing in both layouts, so they
            // are defined once here rather than duplicated into each.
            val open: (EntryInfo) -> Unit = { entry ->
                if (entry.isDirectory) {
                    onOpenDirectory(entry.path.trimEnd('/') + "/")
                } else {
                    sheetFor = entry
                }
            }
            val toggle: (EntryInfo, Boolean) -> Unit = { entry, selected ->
                scope.launch {
                    runCatching { SyncEngine.setSelected(folderId, entry.path, selected) }
                        .onFailure { error = it.message }
                    reloadToken++
                }
            }

            if (grid) {
                LazyVerticalGrid(
                    // Adaptive rather than a fixed column count: the same code
                    // then fills a phone, a foldable and a tablet sensibly.
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current, key = { it.path }) { entry ->
                        EntryTile(
                            folderId = folderId,
                            entry = entry,
                            photoFolder = photoFolder,
                            childCount = childCounts[entry.path],
                            onOpen = { open(entry) },
                            onToggle = { selected -> toggle(entry, selected) },
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(current, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            photoFolder = photoFolder,
                            childCount = childCounts[entry.path],
                            onOpen = { open(entry) },
                            onToggle = { selected -> toggle(entry, selected) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: EntryInfo,
    photoFolder: Boolean,
    /** Entries in this directory, or null until the count has been read. */
    childCount: Int?,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A folder and a file used to be distinguished only by a trailing
        // slash, which is easy to miss when scanning a long list. The icon
        // carries that now, so the slash is gone.
        EntryIcon(entry, size = 24.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                entry.subtitle(photoFolder, childCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Pinning writes to .stignore, which a photo folder has no room for:
        // the checkbox would only ever report an error.
        if (!photoFolder) {
            Checkbox(
                checked = entry.isSelected,
                onCheckedChange = onToggle,
            )
        }
    }
}

/**
 * One entry as a grid cell.
 *
 * The thumbnail is the whole point of the grid: a folder of photos is a list of
 * meaningless file names until you can see them. Everything else falls back to
 * the same icon the list uses.
 */
@Composable
private fun EntryTile(
    folderId: String,
    entry: EntryInfo,
    photoFolder: Boolean,
    childCount: Int?,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Square regardless of the image: ragged cell heights make a
                // grid much harder to scan than the cropping costs.
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.isThumbnailable()) {
                Thumbnail(folderId, entry)
            } else {
                EntryIcon(entry, size = 36.dp)
            }
            // Same control as the list's, in the corner rather than the margin:
            // a grid has no margin to put it in.
            if (!photoFolder) {
                Checkbox(
                    checked = entry.isSelected,
                    onCheckedChange = onToggle,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
        Text(
            entry.subtitle(photoFolder, childCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The image itself, pulled through the engine's streaming server exactly as the
 * file sheet's preview is. The icon stays underneath until it decodes, so a
 * cell is never blank and never changes size.
 */
@Composable
private fun Thumbnail(folderId: String, entry: EntryInfo) {
    var url by remember(folderId, entry.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(folderId, entry.path) {
        url = runCatching { SyncEngine.onDemandUrl(folderId, entry.path) }.getOrNull()
    }

    EntryIcon(entry, size = 36.dp)
    val resolved = url ?: return
    SubcomposeAsyncImage(
        model = resolved,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // Both deliberately empty: the icon behind this is the placeholder, and
        // a spinner per cell would make a screen of them flicker.
        loading = {},
        error = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun EntryIcon(entry: EntryInfo, size: Dp) {
    Icon(
        painter = painterResource(
            if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file,
        ),
        contentDescription = stringResource(
            if (entry.isDirectory) R.string.cd_folder else R.string.cd_file,
        ),
        tint = if (entry.isDirectory) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(size),
    )
}

/**
 * Whether a cell should try to show the file itself.
 *
 * Capped by size because a grid fetches every visible cell at once, and an
 * entry that is not on the device means the whole image crosses the network
 * before anything can be decoded -- one oversized photo per cell would turn
 * scrolling into a download.
 */
private fun EntryInfo.isThumbnailable(): Boolean =
    !isDirectory &&
        size <= THUMBNAIL_LIMIT_BYTES &&
        name.substringAfterLast('.', "").lowercase() in imageExtensions

private const val THUMBNAIL_LIMIT_BYTES = 8L * 1024 * 1024

@Composable
private fun EntryInfo.subtitle(photoFolder: Boolean, childCount: Int?): String {
    val lead = when {
        // "folder" until the count arrives, so the row never changes height or
        // sits without a subtitle while the index is queried.
        isDirectory -> when (childCount) {
            null -> stringResource(R.string.entry_folder)
            0 -> stringResource(R.string.entry_folder_empty)
            else -> pluralStringResource(R.plurals.entry_items, childCount, childCount)
        }
        // The engine reports a file on a virtual filesystem as not
        // present, because its path does not exist on disk. For a photo
        // folder that is backwards: this device is where it comes from.
        photoFolder -> "${formatSize(size)} · in your photo library"
        isLocallyPresent -> "${formatSize(size)} · on device"
        else -> "${formatSize(size)} · not downloaded"
    }
    // Worth calling out: a conflict copy is a second version of a file that
    // changed in two places, and it is easy to mistake for a stray duplicate.
    return if (isConflictCopy) "$lead · conflict copy" else lead
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}
