package com.apexlions.music

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

@Composable
internal fun SyncedLyricsPane(
    lrcText: String,
    plainLyrics: String,
    positionMs: Long,
    credits: List<Credit>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = remember(lrcText) { LrcParser.parse(lrcText) }
    val activeIndex = remember(lines, positionMs) { LrcParser.activeIndex(lines, positionMs) }
    val state = rememberLazyListState()

    LaunchedEffect(activeIndex, lines.size) {
        if (activeIndex >= 0 && lines.isNotEmpty()) {
            state.animateScrollToItem(max(0, activeIndex - 2))
        }
    }

    if (lines.isEmpty()) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 24.dp),
        ) {
            item {
                Text(
                    plainLyrics.ifBlank { "Bu şarkı için senkronize söz bulunmuyor." },
                    color = Color.White,
                    fontSize = 25.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                CreditsBlock(credits)
            }
        }
        return
    }

    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 150.dp, bottom = 220.dp),
    ) {
        itemsIndexed(lines, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
            val active = index == activeIndex
            val passed = activeIndex >= 0 && index < activeIndex
            Text(
                text = line.text,
                color = Color.White,
                fontSize = if (active) 30.sp else 25.sp,
                lineHeight = if (active) 39.sp else 34.sp,
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (active) 1f else if (passed) .42f else .28f)
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 11.dp)
                    .animateContentSize(),
            )
        }
        item { CreditsBlock(credits) }
    }
}

@Composable
private fun CreditsBlock(credits: List<Credit>) {
    if (credits.isEmpty()) return
    Spacer(Modifier.height(34.dp))
    Column(Modifier.fillMaxWidth()) {
        Text("Künye", color = Color.White.copy(alpha = .62f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        credits.forEach { credit ->
            Text(
                "${credit.role}: ${credit.names.joinToString(", ")}",
                color = Color.White.copy(alpha = .48f),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
