package ch.steigis.dinghy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.steigis.dinghy.R

/**
 * The grouped-list vocabulary the Devices and Folders tabs are both built from.
 *
 * Shared rather than copied: the two tabs are meant to look like the same app,
 * and the quickest way for that to stop being true is for each to own its own
 * idea of what a row is.
 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** A row that leads somewhere: icon, two lines, and something on the right. */
@Composable
internal fun NavigationRow(
    iconRes: Int,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = { RowChevron() },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun RowChevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * How often a screen re-reads peer state while it is open.
 *
 * Reacting to the engine's state flow alone is not enough: it carries counts,
 * and it is a data class, so a peer whose name or last-seen changed without
 * changing any count produces a value equal to the previous one, which a
 * StateFlow drops. Anything that shows per-peer detail would then sit stale
 * until a count happened to move.
 */
internal const val PEER_REFRESH_MILLIS = 3_000L

/**
 * The "Add …" button that closes a list. A card of its own rather than a row
 * inside the list: adding is not one of the things listed.
 */
@Composable
internal fun AddCard(text: String, enabled: Boolean, onClick: () -> Unit) {
    // Disabled has to look disabled: in primary colour it reads as tappable and
    // then does nothing, which is worse than being visibly unavailable.
    val tint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_plus),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}

/** Placeholder text for a list with nothing in it yet. */
@Composable
internal fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
