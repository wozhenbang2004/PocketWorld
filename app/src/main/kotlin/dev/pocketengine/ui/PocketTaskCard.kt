package dev.pocketengine.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PocketTaskCard(
    title: String, status: String, context: String, time: String,
    attention: String?, actionLabel: String, failed: Boolean,
    onOpen: () -> Unit
) {
    val statusColor = if (failed) Danger else if (attention != null) ActionInk else SecondaryInk
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (attention != null) ActionInk.copy(alpha = .35f) else Line)
    ) {
        Column(Modifier.clickable(onClick = onOpen).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else if (attention != null) ActionSurface else Soft,
                    shape = RoundedCornerShape(6.dp)) {
                    Text(status, color = statusColor, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(context, color = SecondaryInk, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp))
            if (attention != null) Text(attention, color = statusColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, color = SecondaryInk, fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
