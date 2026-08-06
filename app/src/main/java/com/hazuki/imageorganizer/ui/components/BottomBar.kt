package com.hazuki.imageorganizer.ui.components

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
    onClearSelectionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        // ナビゲーションバー(ジェスチャーバー含む)分の余白を確保。
        // これが無いと、システムのナビゲーションバーの当たり判定にボタンが重なり、タップが反応しなくなる。
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        color = FujiSurfaceVariant,
        tonalElevation = 3.dp
    ) {
        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 読込・グループ計算が終わっていない状態で移動/削除/リネームを行うと、
                // 内部データが未確定でクラッシュすることがあるため、完了するまで押せないようにする
                BottomBarAction(Icons.Filled.DriveFileMove, "移動", onMoveClick, enabled = actionsEnabled)
                BottomBarAction(Icons.Filled.FolderZip, "ZIP化", onZipClick, enabled = actionsEnabled)
                BottomBarAction(Icons.Filled.Delete, "削除", onDeleteClick, enabled = actionsEnabled)
                BottomBarAction(Icons.Filled.DriveFileRenameOutline, "リネーム", onRenameSelectedClick, enabled = actionsEnabled)
                // テキスト検索の「次を検索」のように、押すたびに選択中の画像の位置を順番に巡回する
                BottomBarAction(Icons.Filled.CenterFocusStrong, "選択へ", onJumpToSelectedClick)
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

@Composable
private fun BottomBarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val tint = if (enabled) FujiPrimaryDark else DisabledContent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}
