package com.example.yourapp.ui // ご自身のUIパッケージ名に変更してください

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 確認ダイアログを出すためのアクション種別
 */
sealed class RenameMenuAction {
    object RenameMoveToFolder : RenameMenuAction() // 連番→📁[ラベル]
    object RenameOnly : RenameMenuAction()         // Rename→[ラベル]連番
    object DeleteFiles : RenameMenuAction()        // 削除
    object RenameGroupLabel : RenameMenuAction()   // フォルダ内一括変更
}

/**
 * =====================================================================
 * 【選択メニュー＆確認ダイアログのUIコンポーネント】
 *
 * @param label               現在のラベル名（例: "猫"）
 * @param selectedCount       現在選択されているファイル数
 * @param currentFolderName   現在開いているフォルダ名（ダイアログの表示用）
 * @param isSubfolder         下層フォルダ内にいるかどうか（一括変更ボタンの有効/無効判定用）
 * @param onRenameMove        「連番→📁ラベル」が選ばれて確認ダイアログで「実行」された時の処理
 * @param onRenameOnly        「Rename→ラベル連番」が選ばれて「実行」された時の処理
 * @param onCopyRequested     「コピー」が選ばれた時の処理（フォルダ選択ランチャーを起動）
 * @param onMoveRequested     「移動」が選ばれた時の処理（フォルダ選択ランチャーを起動）
 * @param onDeleteConfirmed   「削除」が選ばれて確認ダイアログで「削除する」が押された時の処理
 * @param onRenameGroupLabel  「一括変更」が選ばれて「実行」された時の処理
 * =====================================================================
 */
@Composable
fun RenameActionMenu(
    label: String,
    selectedCount: Int,
    currentFolderName: String,
    isSubfolder: Boolean = true,
    onRenameMove: () -> Unit,
    onRenameOnly: () -> Unit,
    onCopyRequested: () -> Unit,
    onMoveRequested: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onRenameGroupLabel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<RenameMenuAction?>(null) }

    Box(modifier = modifier) {
        // 「●枚選択」ボタン
        TextButton(onClick = { showMenu = true }) {
            Text("${selectedCount}枚選択")
        }

        // ドロップダウンメニュー
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            // 1. 連番にしてラベル名フォルダへ移動
            DropdownMenuItem(
                text = { Text("連番→📁$label") },
                onClick = {
                    showMenu = false
                    pendingAction = RenameMenuAction.RenameMoveToFolder
                }
            )

            // 2. 移動せず、その場で連番リネーム
            DropdownMenuItem(
                text = { Text("Rename→${label}連番") },
                onClick = {
                    showMenu = false
                    pendingAction = RenameMenuAction.RenameOnly
                }
            )

            // 3. コピー（フォルダ選択へ）
            DropdownMenuItem(
                text = { Text("コピー") },
                onClick = {
                    showMenu = false
                    onCopyRequested()
                }
            )

            // 4. 移動（フォルダ選択へ）
            DropdownMenuItem(
                text = { Text("移動") },
                onClick = {
                    showMenu = false
                    onMoveRequested()
                }
            )

            // 5. 削除（赤文字で表示）
            DropdownMenuItem(
                text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    pendingAction = RenameMenuAction.DeleteFiles
                }
            )

            // 6. フォルダ内の一括ラベル変更
            val canRenameGroup = isSubfolder && selectedCount > 0
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
                        pendingAction = RenameMenuAction.RenameGroupLabel
                    }
                }
            )
        }
    }

    // ===== 実行前の確認ダイアログ =====
    pendingAction?.let { action ->
        val title = if (action is RenameMenuAction.DeleteFiles) "削除の確認" else "確認"
        val message = when (action) {
            is RenameMenuAction.RenameMoveToFolder ->
                "${selectedCount}枚を連番リネームして「$label」フォルダに移動します。よろしいですか？"
            is RenameMenuAction.RenameOnly ->
                "${selectedCount}枚を「${label}_連番」の形式にリネームします（移動はしません）。よろしいですか？"
            is RenameMenuAction.DeleteFiles ->
                "${selectedCount}枚を削除します。この操作は元に戻せません。よろしいですか？"
            is RenameMenuAction.RenameGroupLabel ->
                "フォルダ「$currentFolderName」の対象ファイルとフォルダ名を「$label」に一括変更します。よろしいですか？"
        }
        val confirmText = if (action is RenameMenuAction.DeleteFiles) "削除する" else "実行"

        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    when (action) {
                        is RenameMenuAction.RenameMoveToFolder -> onRenameMove()
                        is RenameMenuAction.RenameOnly -> onRenameOnly()
                        is RenameMenuAction.DeleteFiles -> onDeleteConfirmed()
                        is RenameMenuAction.RenameGroupLabel -> onRenameGroupLabel()
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
