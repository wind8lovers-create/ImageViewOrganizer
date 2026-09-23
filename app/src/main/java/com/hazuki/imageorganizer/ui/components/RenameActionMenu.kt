package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * =====================================================================
 * 【選択メニュー＆確認ダイアログのUIコンポーネント】
 *
 * 選択時に画面上部に出現するアイコンのみのメニュー。
 * 各アイコンをタップするとドロップダウンメニューが出現し、
 * さらにメニュー項目をタップすると確認ダイアログが表示されます。
 *
 * @param selectedCount       現在選択されているファイル数
 * @param label               現在のラベル名（例: "猫"）
 * @param currentFolderName   現在開いているフォルダ名（ダイアログ表示用）
 * @param isSubfolder         下層フォルダ内にいるかどうか（一括変更ボタン有効/無効判定用）
 * @param onRenameMove        「[連番] →📁ラベル」実行時のコールバック
 * @param onRenameOnly        「リネーム →ラベル [連番]」実行時のコールバック
 * @param onCopyRequested     「コピー」選択時のコールバック（フォルダピッカー起動等）
 * @param onMoveRequested     「移動」選択時のコールバック（フォルダピッカー起動等）
 * @param onDeleteConfirmed   「削除」実行時のコールバック
 * @param onRenameGroupLabel  「一括変更」実行時のコールバック
 * =====================================================================
 */
@Composable
fun RenameActionMenu(
    selectedCount: Int,
    label: String,
    currentFolderName: String,
    isSubfolder: Boolean = true,
    onRenameMove: () -> Unit,
    onRenameOnly: () -> Unit,
    onCopyRequested: () -> Unit,
    onMoveRequested: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDefragGroupNumbers: () -> Unit = {},
    onRenameGroupLabel: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ドロップダウンメニューの表示状態
    var showMenu by remember { mutableStateOf(false) }
    
    // 確認ダイアログ用：実行待ちの操作種別
    var pendingAction by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier) {
        // アイコンボタン（メニュー表示用）
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "操作メニュー",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // ドロップダウンメニュー
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            // 1. [連番] →📁[ラベル]フォルダへ移動
            DropdownMenuItem(
                text = { Text("[連番] →📁$label") },
                onClick = {
                    showMenu = false
                    pendingAction = "renameMove"
                }
            )

            // 2. リネーム →[ラベル] [連番]（移動なし）
            DropdownMenuItem(
                text = { Text("リネーム →$label [連番]") },
                onClick = {
                    showMenu = false
                    pendingAction = "renameOnly"
                }
            )

            // 3. コピー
            DropdownMenuItem(
                text = { Text("コピー") },
                onClick = {
                    showMenu = false
                    onCopyRequested()
                }
            )

            // 4. 移動
            DropdownMenuItem(
                text = { Text("移動") },
                onClick = {
                    showMenu = false
                    onMoveRequested()
                }
            )

            // 5. 削除（赤文字）
            DropdownMenuItem(
                text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    pendingAction = "delete"
                }
            )

            // 6. グループ番号整理（デフラグリナンバー）
            val canRenameGroup = isSubfolder && selectedCount > 0
            DropdownMenuItem(
                text = {
                    Text(
                        text = "[$label] GP番号整理",
                        color = if (canRenameGroup) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                },
                onClick = {
                    if (canRenameGroup) {
                        showMenu = false
                        pendingAction = "defragGroupNumbers"
                    }
                }
            )

            // 7. フォルダ内一括ラベル変更（下層フォルダの場合のみ有効）
            DropdownMenuItem(
                text = {
                    Text(
                        text = "[$label]📁一括変更",
                        color = if (canRenameGroup) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                },
                onClick = {
                    if (canRenameGroup) {
                        showMenu = false
                        pendingAction = "renameGroup"
                    }
                }
            )
        }
    }

    // ===== 実行前の確認ダイアログ =====
    pendingAction?.let { action ->
        // ダイアログのタイトル・メッセージを決める
        val (title, message, confirmText) = when (action) {
            "renameMove" -> {
                Triple(
                    "確認",
                    "${selectedCount}枚を連番リネームして「$label」フォルダに移動します。よろしいですか？",
                    "実行"
                )
            }
            "renameOnly" -> {
                Triple(
                    "確認",
                    "${selectedCount}枚を「${label}_連番」の形式にリネームします（移動はしません）。よろしいですか？",
                    "実行"
                )
            }
            "delete" -> {
                Triple(
                    "削除の確認",
                    "${selectedCount}枚を削除します。この操作は元に戻せません。よろしいですか？",
                    "削除する"
                )
            }
            "defragGroupNumbers" -> {
                Triple(
                    "グループ番号整理の確認",
                    "フォルダ内の対象ファイルのグループ番号を「01」から連続するように整理し、ラベル名を「$label」に統一します。よろしいですか？",
                    "実行"
                )
            }
            "renameGroup" -> {
                Triple(
                    "確認",
                    "フォルダ「$currentFolderName」の対象ファイルとフォルダ名を「$label」に一括変更します。よろしいですか？",
                    "実行"
                )
            }
            else -> return@let
        }

        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    when (action) {
                        "renameMove" -> onRenameMove()
                        "renameOnly" -> onRenameOnly()
                        "delete" -> onDeleteConfirmed()
                        "defragGroupNumbers" -> onDefragGroupNumbers()
                        "renameGroup" -> onRenameGroupLabel()
                    }
                }) {
                    Text(confirmText)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
