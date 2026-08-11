package com.hazuki.imageorganizer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hazuki.imageorganizer.data.ImageItem
import com.hazuki.imageorganizer.data.ThumbnailSize
import com.hazuki.imageorganizer.ui.components.ImageGrid
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.FujiSurfaceVariant
import com.hazuki.imageorganizer.viewmodel.ClassificationTile

/**
 * グループ内画面(手動グルーピングの1グループの中身)。
 *
 * 同じカテゴリ(例:"C")に複数のグループがある場合、左右スワイプで隣のグループ
 * (C_01 ⇔ C_02 ⇔ C_03 ...)に切り替えられる。実際に画像の追加/削除/スライドショーなどの
 * 操作ができるのは「現在表示中のグループ」だけで、スワイプ中に見える隣のページは
 * 代表画像+名前だけの軽いプレビュー表示にとどめている(操作の混線を防ぐため)。
 *
 * ・タップ: 拡大表示(通常一覧と同じ)
 * ・長押し / 選択中の画像タップ: 「画像削除」対象として選ぶ(グループから外すだけで、端末上のファイルは消えない)
 * ・「サムネ指定」ボタン: 選んだ1枚を、分類一覧でのこのグループの代表画像にする
 * ・「画像追加」ボタン: 通常一覧に切り替わり、同じカテゴリの画像だけ選べる状態で画像を追加できる
 * ・「スライドショー」ボタン: このグループの画像だけを再生する
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupDetailScreen(
    groupDisplayName: String,
    entries: List<ImageItem>,
    thumbnailSize: ThumbnailSize,
    selectedIds: Set<Long>,
    siblingTiles: List<ClassificationTile>, // 同じカテゴリのグループ一覧(連番順)。スワイプの対象。
    currentGroupKey: String,
    onBack: () -> Unit,
    onTapImage: (Int) -> Unit,
    onToggleSelected: (Long) -> Unit,
    onSortClick: () -> Unit,
    onAddImagesClick: () -> Unit,
    onDeleteSelectedClick: () -> Unit,
    onSlideshowClick: () -> Unit,
    onSetThumbnailClick: (Long) -> Unit,
    onPageSettled: (groupKey: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (siblingTiles.isEmpty()) {
        // データ不整合等でsiblingTilesが空の場合でも、最低限現在のグループだけは表示できるようにする
        GroupDetailContent(
            groupDisplayName = groupDisplayName,
            entries = entries,
            thumbnailSize = thumbnailSize,
            selectedIds = selectedIds,
            onBack = onBack,
            onTapImage = onTapImage,
            onToggleSelected = onToggleSelected,
            onSortClick = onSortClick,
            onAddImagesClick = onAddImagesClick,
            onDeleteSelectedClick = onDeleteSelectedClick,
            onSlideshowClick = onSlideshowClick,
            onSetThumbnailClick = onSetThumbnailClick,
            modifier = modifier
        )
        return
    }

    val siblingKeys = siblingTiles.map { it.group.key }
    val startPage = siblingKeys.indexOf(currentGroupKey).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startPage) { siblingTiles.size }

    // ページが確定(スワイプ完了)したら、そのグループの実データ(entries/選択状態など)をViewModel側に読み込ませる
    LaunchedEffect(pagerState.currentPage) {
        val key = siblingKeys.getOrNull(pagerState.currentPage)
        if (key != null && key != currentGroupKey) onPageSettled(key)
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize()
    ) { page ->
        val tile = siblingTiles[page]
        if (tile.group.key == currentGroupKey) {
            GroupDetailContent(
                groupDisplayName = groupDisplayName,
                entries = entries,
                thumbnailSize = thumbnailSize,
                selectedIds = selectedIds,
                onBack = onBack,
                onTapImage = onTapImage,
                onToggleSelected = onToggleSelected,
                onSortClick = onSortClick,
                onAddImagesClick = onAddImagesClick,
                onDeleteSelectedClick = onDeleteSelectedClick,
                onSlideshowClick = onSlideshowClick,
                onSetThumbnailClick = onSetThumbnailClick,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SiblingPreview(tile = tile)
        }
    }
}

/** スワイプ中に見える、まだ確定していない隣のグループの軽量プレビュー(代表画像+名前のみ) */
@Composable
private fun SiblingPreview(tile: ClassificationTile) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .windowInsetsTopHeight(WindowInsets.statusBars)
        )
        Surface(color = FujiSurfaceVariant, tonalElevation = 2.dp) {
            Text(
                text = "${tile.group.displayName}(${tile.group.imageIds.size}枚)",
                style = MaterialTheme.typography.titleSmall,
                color = FujiPrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (tile.representative != null) {
                AsyncImage(
                    model = tile.representative.uri,
                    contentDescription = tile.group.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun GroupDetailContent(
    groupDisplayName: String,
    entries: List<ImageItem>,
    thumbnailSize: ThumbnailSize,
    selectedIds: Set<Long>,
    onBack: () -> Unit,
    onTapImage: (Int) -> Unit,
    onToggleSelected: (Long) -> Unit,
    onSortClick: () -> Unit,
    onAddImagesClick: () -> Unit,
    onDeleteSelectedClick: () -> Unit,
    onSlideshowClick: () -> Unit,
    onSetThumbnailClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // ステータスバー(時計・電波・バッテリー)の視認性を確保するための黒帯。
        // OrganizerTopBarと同じ処理(この帯が無いと、システムアイコンとヘッダーのボタンが重なって操作できなくなる)。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .windowInsetsTopHeight(WindowInsets.statusBars)
        )
        Surface(color = FujiSurfaceVariant, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "分類一覧に戻る", tint = FujiPrimaryDark)
                }
                Text(
                    text = "$groupDisplayName(${entries.size}枚)",
                    style = MaterialTheme.typography.titleSmall,
                    color = FujiPrimaryDark,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                IconButton(onClick = onSortClick) {
                    Icon(Icons.Filled.Sort, contentDescription = "並び替え", tint = FujiPrimaryDark)
                }
                IconButton(onClick = onSlideshowClick) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = "このグループでスライドショー", tint = FujiPrimaryDark)
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                Text(
                    text = "画像がありません",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = FujiPrimaryDark.copy(alpha = 0.7f)
                )
            } else {
                ImageGrid(
                    entries = entries,
                    thumbnailSize = thumbnailSize,
                    selectedIds = selectedIds,
                    selectionMode = selectedIds.isNotEmpty(),
                    onTap = { index ->
                        val id = entries.getOrNull(index)?.id ?: return@ImageGrid
                        if (selectedIds.isNotEmpty()) onToggleSelected(id) else onTapImage(index)
                    },
                    onLongPress = { index ->
                        val id = entries.getOrNull(index)?.id ?: return@ImageGrid
                        onToggleSelected(id)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Surface(color = FujiSurfaceVariant, tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 「サムネ指定」は、ちょうど1枚だけ選んでいる時だけ押せる(代表画像は1枚しか設定できないため)
                if (selectedIds.size == 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onSetThumbnailClick(selectedIds.first()) }) {
                            Icon(Icons.Filled.Star, contentDescription = "サムネ指定", tint = FujiPrimaryDark)
                        }
                        Text("サムネ指定", style = MaterialTheme.typography.labelSmall, color = FujiPrimaryDark)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAddImagesClick) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "画像追加", tint = FujiPrimaryDark)
                    }
                    Text("画像追加", style = MaterialTheme.typography.labelSmall, color = FujiPrimaryDark)
                }
                if (selectedIds.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDeleteSelectedClick) {
                            Icon(Icons.Filled.Delete, contentDescription = "画像削除", tint = FujiPrimaryDark)
                        }
                        Text("画像削除(${selectedIds.size})", style = MaterialTheme.typography.labelSmall, color = FujiPrimaryDark)
                    }
                }
            }
        }
    }
}
