package com.example.realtimetranslator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.realtimetranslator.R
import com.example.realtimetranslator.model.LensMode

/**
 * Online translation is not wired up yet, so its chip is shown disabled rather
 * than hidden - the mode still exists, it just has no provider behind it.
 */
@Composable
fun ModePicker(
    mode: LensMode,
    onSelect: (LensMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .padding(8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        LensMode.entries.forEach { candidate ->
            val enabled = candidate == LensMode.OFFLINE
            val selected = candidate == mode

            Text(
                text = when (candidate) {
                    LensMode.ONLINE -> stringResource(R.string.mode_online)
                    LensMode.OFFLINE -> stringResource(R.string.mode_offline)
                },
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier
                    .padding(4.dp)
                    .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                    .clickable(enabled = enabled) { onSelect(candidate) }
                    .background(
                        if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}
