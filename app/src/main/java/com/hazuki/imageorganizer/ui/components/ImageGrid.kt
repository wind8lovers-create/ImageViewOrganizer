package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hazuki.imageorganizer.data.DisplayEntry
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.theme.GroupOutlineAmber
import com.hazuki.imageorganizer.ui.theme.GroupOutlineYellow
import com.hazuki.imageorganizer.ui.theme.SelectionOverlay

private const val OUTLINE_WIDTH_DP = 3

@Composable
fun ImageGrid(
    entries: List<DisplayEntry>,
    thumbnailSize: ThumbnailSize,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onGroupCheckboxTap: (groupId: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = thumbnailSize.dp.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
    ) {
        items(count = entries.size, key = { idx ->
            when (val e = entries[idx]) {
                is DisplayEntry.Single -> e.image.id
                is DisplayEntry.Grouped -> e.image.id
            }
        }) { index ->
            // 列数はGridCells.Adaptiveのため実行時に厳密取得できないので、
            // LazyGridStateのlayoutInfoから直近のスパン情報を概算する。
            val columns = state.layoutInfo.visibleItemsInfo
                .maxOfOrNull { it.column ?: 0 }
                ?.plus(1)?.takeIf { it > 0 } ?: 1

            GridCellContent(
                entries = entries,
                index = index,
                columns = columns,
                thumbnailSize = thumbnailSize,
                selectedIds = selectedIds,
                selectionMode = selectionMode,
                onTap = onTap,
                onLongPress = onLongPress,
                onGroupCheckboxTap = onGroupCheckboxTap
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCellContent(
    entries: List<DisplayEntry>,
    index: Int,
    columns: Int,
    thumbnailSize: ThumbnailSize,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onGroupCheckboxTap: (groupId: Int) -> Unit
) {
    val entry = entries[index]
    val image = when (entry) {
        is DisplayEntry.Single -> entry.image
        is DisplayEntry.Grouped -> entry.image
    }
    val isSelected = image.id in selectedIds

    val groupId = (entry as? DisplayEntry.Grouped)?.groupId
    val groupColor = (entry as? DisplayEntry.Grouped)?.let {
        if (it.colorIndex == 0) GroupOutlineYellow else GroupOutlineAmber
    }

    fun groupIdAt(i: Int): Int? {
        if (i < 0 || i >= entries.size) return null
        return (entries[i] as? DisplayEntry.Grouped)?.groupId
    }

    val row = index / columns
    val col = index % columns

    val drawTop = groupId != null && groupIdAt(index - columns) != groupId
    val drawBottom = groupId != null && groupIdAt(index + columns) != groupId
    val drawLeft = groupId != null && (col == 0 || groupIdAt(index - 1) != groupId)
    val drawRight = groupId != null && (col == columns - 1 || groupIdAt(index + 1) != groupId)

    Box(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .combinedClickable(
                onClick = {
                    if (selectionMode) onTap(index) else onTap(index)
                },
                onLongClick = { onLongPress(index) }
            )
            .then(
                if (groupColor != null) Modifier.drawBehind {
                    val strokeWidthPx = OUTLINE_WIDTH_DP.dp.toPx()
                    val half = strokeWidthPx / 2
                    if (drawTop) drawLine(groupColor, Offset(0f, half), Offset(size.width, half), strokeWidthPx)
                    if (drawBottom) drawLine(groupColor, Offset(0f, size.height - half), Offset(size.width, size.height - half), strokeWidthPx)
                    if (drawLeft) drawLine(groupColor, Offset(half, 0f), Offset(half, size.height), strokeWidthPx)
                    if (drawRight) drawLine(groupColor, Offset(size.width - half, 0f), Offset(size.width - half, size.height), strokeWidthPx)
                } else Modifier
            )
    ) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val targetPx = with(density) { thumbnailSize.dp.dp.roundToPx() }

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(image.uri)
                // 表示に必要なサイズだけデコードすることで、フル解像度デコードの重さを避ける
                // (メモリ/ディスクキャッシュも自動で効くため、再表示は高速)
                .size(targetPx, targetPx)
                .crossfade(true)
                .build(),
            contentDescription = image.displayName,
            modifier = Modifier.fillMaxSize()
        )

        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(SelectionOverlay))
        }

        if (selectionMode) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
            )
        }

        // グループ外枠の左上に、グループ全体選択用の小さなチェックボックスを1回だけ表示
        if (groupId != null && drawTop && drawLeft) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .size(16.dp)
                    .background(groupColor ?: Color.Yellow)
                    .combinedClickable(
                        onClick = { onGroupCheckboxTap(groupId) },
                        onLongClick = { onGroupCheckboxTap(groupId) }
                    )
            )
        }
    }
}
