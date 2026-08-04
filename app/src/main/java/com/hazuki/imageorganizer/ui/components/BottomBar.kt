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
import androidx.compose.material.icons.filled.Extension
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
    currentSortLabel: String,
    onSortClick: () -> Unit,
    onSelectClick: () -> Unit,
    onRenameClick: () -> Unit,
    onExtensionClick: () -> Unit,
    onMoveClick: () -> Unit,
    onZipClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameSelectedClick: () -> Unit,
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
                BottomBarAction(Icons.Filled.DriveFileMove, "移動", onMoveClick)
                BottomBarAction(Icons.Filled.FolderZip, "ZIP化", onZipClick)
                BottomBarAction(Icons.Filled.Delete, "削除", onDeleteClick)
                BottomBarAction(Icons.Filled.DriveFileRenameOutline, "リネーム", onRenameSelectedClick)
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
                BottomBarAction(Icons.Filled.Extension, "拡張", onExtensionClick)
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
