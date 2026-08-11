package com.hazuki.imageorganizer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.util.ClassificationColorUtil
import com.hazuki.imageorganizer.viewmodel.ClassificationTile

/**
 * 分類一覧画面。
 * ・グリッド型(通常の画像一覧と同じ見た目)で、カテゴリ(A〜Z)ごとに島状にまとまって表示される
 * ・タップ: そのグループの中に入る(GroupDetailScreenへ)
 * ・長押し: そのカテゴリ全体を通してスライドショー再生
 * ・グループ名を長押し(代表画像自体ではなく名前ラベル部分)するとリネームダイアログが開く
 *   → 実装簡略化のため、代表画像の長押しは「スライドショー」に、名前ラベルの長押しは「リネーム」に
 *     役割を分けている(同じ長押しジェスチャーが競合しないようにするため)
 */
@Composable
fun ClassificationListScreen(
    tiles: List<ClassificationTile>,
    thumbnailSize: ThumbnailSize,
    onTileTap: (groupKey: String) -> Unit,
    onTileLongPress: (category: Char) -> Unit,
    onNameLongPress: (groupKey: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tiles.isEmpty()) {
        Box(modifier = modifier.fillMaxSize()) {
            Text(
                text = "まだ分類(グループ)がありません。\n画像一覧で長押しして選択し、「分類登録」から作成できます。",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark.copy(alpha = 0.8f)
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = thumbnailSize.dp.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)
    ) {
        items(count = tiles.size, key = { idx -> tiles[idx].group.key }) { index ->
            ClassificationTileCell(
                tile = tiles[index],
                thumbnailSize = thumbnailSize,
                onTap = onTileTap,
                onLongPress = onTileLongPress,
                onNameLongPress = onNameLongPress
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClassificationTileCell(
    tile: ClassificationTile,
    thumbnailSize: ThumbnailSize,
    onTap: (String) -> Unit,
    onLongPress: (Char) -> Unit,
    onNameLongPress: (String) -> Unit
) {
    val color = ClassificationColorUtil.colorForCategory(tile.group.category)

    Box(
        modifier = Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .border(2.dp, color)
            .combinedClickable(
                onClick = { onTap(tile.group.key) },
                onLongClick = { onLongPress(tile.group.category) }
            )
    ) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val targetPx = with(density) { thumbnailSize.dp.dp.roundToPx() }

        if (tile.representative != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(tile.representative.uri)
                    .size(targetPx, targetPx)
                    .crossfade(true)
                    .build(),
                contentDescription = tile.group.displayName,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(color.copy(alpha = 0.2f)))
        }

        // グループ名(枚数付き)。ここだけ長押しでリネームダイアログを開く。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .combinedClickable(
                    onClick = { onTap(tile.group.key) },
                    onLongClick = { onNameLongPress(tile.group.key) }
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = "${tile.group.displayName}(${tile.group.imageIds.size})",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
