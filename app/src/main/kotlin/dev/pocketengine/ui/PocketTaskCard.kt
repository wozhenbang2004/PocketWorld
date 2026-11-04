package dev.pocketengine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
    // A task is one object, not a collection of status chips and equal-weight actions.
    // The caller supplies the real status/attention; this surface only opens its review.
    val needsAttention = attention != null
    val metadata = listOfNotNull(
        attention?.takeIf(String::isNotBlank) ?: status.takeIf(String::isNotBlank),
        context.takeIf(String::isNotBlank),
        time.takeIf(String::isNotBlank)
    ).joinToString(" · ")
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = if (needsAttention) 8.dp else 0.dp)
            .then(if (needsAttention) Modifier.pocketDepth(PocketDepth.CONTENT, 12.dp) else Modifier),
        shape = shape,
        color = if (needsAttention) MaterialTheme.colorScheme.surface else Color.Transparent,
        contentColor = Ink
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = Ink, fontSize = 15.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)) {
                    Text(actionLabel, color = if (needsAttention || failed) Ink else SecondaryInk,
                        fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
                    AffectGlyph("chevron-right", Modifier.size(12.dp), SecondaryInk)
                }
            }
            if (metadata.isNotBlank()) Text(metadata,
                color = if (needsAttention) Ink else SecondaryInk,
                fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp))
        }
    }
}
