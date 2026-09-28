package com.nextbench.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small unread/count badge (e.g. on the messages tab). Hidden when [count] is 0. */
@Composable
fun NbCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = NbTheme.colors.brandPink,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/** A labeled status pill (e.g. "SOLD", "NEW"). */
@Composable
fun NbPill(
    label: String,
    modifier: Modifier = Modifier,
    contentColor: Color = NbTheme.colors.brandTeal,
    containerColor: Color = contentColor.copy(alpha = 0.12f),
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier = modifier
            .clip(RoundedCornerShape(NbDimens.radiusFull))
            .background(containerColor)
            .padding(horizontal = NbDimens.space8, vertical = NbDimens.space2),
    )
}
