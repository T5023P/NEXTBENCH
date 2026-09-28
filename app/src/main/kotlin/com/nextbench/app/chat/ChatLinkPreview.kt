package com.nextbench.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nextbench.core.designsystem.NbDimens
import com.nextbench.core.designsystem.NbTheme
import com.nextbench.data.firebase.LinkPreview

@Composable
fun ChatLinkPreview(
    preview: LinkPreview,
    isViewer: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (isViewer) Color.White else NbTheme.colors.ink
    Column(
        modifier = modifier
            .widthIn(max = 272.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(NbDimens.radiusSm))
            .background(if (isViewer) Color.Black.copy(alpha = 0.1f) else NbTheme.colors.surfaceSoft)
            .clickable { onOpen(preview.url) },
    ) {
        preview.image?.takeIf(String::isNotBlank)?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = preview.title ?: "Link preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.91f),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = NbDimens.space12, vertical = NbDimens.space8),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(NbDimens.space2),
        ) {
            preview.siteName?.takeIf(String::isNotBlank)?.let { site ->
                Text(site, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = contentColor.copy(alpha = 0.64f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            preview.title?.takeIf(String::isNotBlank)?.let { title ->
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = contentColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            preview.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(description, style = MaterialTheme.typography.bodySmall, color = contentColor.copy(alpha = 0.72f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
