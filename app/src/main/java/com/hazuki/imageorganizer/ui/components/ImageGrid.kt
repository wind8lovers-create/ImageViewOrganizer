package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.theme.FujiOutline
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.SelectionOverlay
import com.hazuki.imageorganizer.util.ClassificationColorUtil
import kotlinx.coroutines.launch

@Composable
fun ImageGrid(
    entries: List<ImageItem>,
    thumbnailSize: ThumbnailSize,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    // ---- 手動グルーピング(分類)機能 ----
    // groupedImageIds: 画像ID -> 所属カテゴリ。枠色の表示に使う(選択モードでなくても常時表示)。
    // dimmedIds: 選択モード中、既に他のグループに入っていて選択できない画像(暗く表示・タップ無効化)。
    groupedImageIds: Map<Long, Char> = emptyMap(),
    dimmedIds: Set<Long> = emptySet()
) {
    val state = gridState
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = thumbnailSize.dp.dp),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
        ) {
            items(count = entries.size, key = { idx -> entries[idx].id }) { index ->
                GridCellContent(
                    entries = entries,
                    index = index,
                    thumbnailSize = thumbnailSize,
                    selectedIds = selectedIds,
                    selectionMode = selectionMode,
                    onTap = onTap,
                    onLongPress = onLongPress,
                    groupCategory = groupedImageIds[entries[index].id],
                    isDimmed = entries[index].id in dimmedIds
                )
            }
        }

        // 右端の位置インジケーター兼ドラッグ用スクロールバー(全体を100%として現在位置を表示)
        if (entries.isNotEmpty()) {
            ScrollPositionBar(
                gridState = state,
                totalItems = entries.size,
                onDragToFraction = { fraction ->
                    val targetIndex = (fraction * (entries.size - 1)).toInt().coerceIn(0, entries.size - 1)
                    coroutineScope.launch { state.scrollToItem(targetIndex) }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun ScrollPositionBar(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    totalItems: Int,
    onDragToFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var trackHeightPx by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var draggingFraction by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    // 現在のスクロール位置を 0f(先頭)〜1f(末尾) の割合として算出
    val currentFraction by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (totalItems <= 1 || visible.isEmpty()) 0f
            else (gridState.firstVisibleItemIndex.toFloat() / (totalItems - 1).toFloat()).coerceIn(0f, 1f)
        }
    }

    val displayFraction = if (isDragging) draggingFraction else currentFraction

    Box(
        modifier = modifier
            .padding(vertical = 8.dp, horizontal = 2.dp)
            .width(20.dp)
            .fillMaxHeight()
            .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
            .pointerInput(totalItems) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                ) { change, _ ->
                    change.consume()
                    if (trackHeightPx > 0f) {
                        val fraction = (change.position.y / trackHeightPx).coerceIn(0f, 1f)
                        draggingFraction = fraction
                        onDragToFraction(fraction)
                    }
                }
            }
    ) {
        // 背景の細い線(トラック)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .fillMaxHeight()
                .background(FujiOutline, shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
        // つまみ(現在位置)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (displayFraction * (trackHeightPx - with(density) { 28.dp.toPx() })).toInt()) }
                .size(width = 20.dp, height = 28.dp)
                .background(FujiPrimaryDark, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCellContent(
    entries: List<ImageItem>,
    index: Int,
    thumbnailSize: ThumbnailSize,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    groupCategory: Char? = null,
    isDimmed: Boolean = false
) {
    val image = entries[index]
    val isSelected = image.id in selectedIds
    // 選択モード中で「暗表示」対象の画像は、タップ/長押しともに無効化する
    // (すでに他のグループに入っている画像を、二重にグループ化させないための仕様)
    val interactionEnabled = !(selectionMode && isDimmed)

    Box(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .let { m ->
                if (groupCategory != null) {
                    m.border(2.dp, ClassificationColorUtil.colorForCategory(groupCategory))
                } else m
            }
            .combinedClickable(
                enabled = interactionEnabled,
                onClick = { onTap(index) },
                onLongClick = { onLongPress(index) }
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

        // 【※3】選択された画像は光度を40%下げて（黒40%透過オーバーレイ）暗く表示し、選択状態を一目で分かりやすくします
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)))
        }

        // 選択モード中、他のグループに既に入っている画像を暗く表示(要件定義Q2)
        if (selectionMode && isDimmed) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
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
    }
}
