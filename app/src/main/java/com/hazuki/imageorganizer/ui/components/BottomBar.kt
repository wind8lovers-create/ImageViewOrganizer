package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazuki.imageorganizer.ui.theme.DisabledContent
import com.hazuki.imageorganizer.ui.theme.FujiPrimary
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.FujiSurfaceVariant

@Composable
fun OrganizerBottomBar(
    selectionMode: Boolean,
    selectedCount: Int,
    sortEnabled: Boolean,
    actionsEnabled: Boolean,
    currentSortLabel: String,
    onSortClick: () -> Unit,
    onSelectClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onZipClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameSelectedClick: () -> Unit,
    onJumpToSelectedClick: () -> Unit,
    onJumpToSelectedLongClick: () -> Unit = {},
    currentJumpIndex: Int? = null,
    onClearSelectionClick: () -> Unit,
    // 「画像追加」モード中(グループ内画面→通常一覧に切り替えて同カテゴリの画像を選ぶ最中)は、
    // 誤って移動/削除などの操作をしてしまわないよう、専用のシンプルな行に差し替える。
    addModeActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        // ナビゲーションバー(ジェスチャーバー含む)分の余白を確保。
        // これが無いと、システムのナビゲーションバーの当たり判定にボタンが重なり、タップが反応しなくなる。
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        color = FujiSurfaceVariant,
        tonalElevation = 3.dp
    ) {
        if (selectionMode && addModeActive) {
            // 画像追加モード専用の行: 「同じカテゴリの画像を選んでください」の案内 + キャンセルのみ
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "同じカテゴリの画像を選択中(${selectedCount}枚)",
                    style = MaterialTheme.typography.labelMedium,
                    color = FujiPrimaryDark
                )
                BottomBarAction(Icons.Filled.Close, "キャンセル", onClearSelectionClick)
            }
        } else if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 読込・グループ計算が終わっていない状態で移動/削除/リネームを行うと、
                // 内部データが未確定でクラッシュすることがあるため、完了するまで押せないようにする
                BottomBarAction(Icons.Filled.DriveFileMove, "移動", onMoveClick, enabled = actionsEnabled)
                BottomBarAction(Icons.Filled.FolderZip, "ZIP", onZipClick, enabled = actionsEnabled)
                BottomBarAction(Icons.Filled.Delete, "削除", onDeleteClick, enabled = actionsEnabled)
                BottomBarAction(Icons.Filled.DriveFileRenameOutline, "RN", onRenameSelectedClick, enabled = actionsEnabled)
                // テキスト検索の「次を検索」のように、押すたびに選択中の画像の位置を順番に巡回する。
                // 長押しで先頭に戻る機能を追加。
                val jumpLabel = if (currentJumpIndex != null) "選択へ(${currentJumpIndex})" else "選択へ"
                BottomBarAction(
                    icon = Icons.Filled.CenterFocusStrong,
                    label = jumpLabel,
                    onClick = onJumpToSelectedClick,
                    onLongClick = onJumpToSelectedLongClick
                )
                BottomBarAction(Icons.Filled.Close, "解除(${selectedCount})", onClearSelectionClick)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomBarAction(
                    icon = Icons.Filled.Sort,
                    label = currentSortLabel,
                    onClick = onSortClick,
                    enabled = sortEnabled
                )
                BottomBarAction(Icons.Filled.CheckBox, "選択", onSelectClick)
                BottomBarAction(Icons.Filled.DriveFileRenameOutline, "リネーム", onRenameClick)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomBarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val tint = if (enabled) FujiPrimaryDark else DisabledContent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp) // タップ範囲を確保
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
