package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 「分類登録」(新規グループ作成)と「グループ名の変更」の両方で使う共用ダイアログ。
 *
 * 要件定義:
 * ・プルダウンでカテゴリ(A〜Z)を選ぶと、そのカテゴリの「次の連番:既存テーマ名」がプレビューされる
 * ・テキスト入力は自由編集可能。空欄のままOKを押すと、連番名(例:"C_04")がそのまま採用される
 * ・OKを押すまでは何度でも編集できる(=リネーム時もこの同じダイアログを再利用する)
 *
 * @param initialCategory 開いた時点で選択されているカテゴリ(リネーム時はそのグループの現在のカテゴリ)
 * @param initialName 開いた時点でのテキスト欄の初期値(新規作成時は空、リネーム時は現在の名前)
 * @param previewFor 「このカテゴリを選んだら次はどんなキー+テーマ名になるか」を返す関数(例: "C" -> "C_04:神社")
 * @param onConfirm カテゴリと入力名でOKが押されたときのコールバック
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassificationNameDialog(
    title: String,
    initialCategory: Char,
    initialName: String,
    previewFor: (Char) -> String,
    onConfirm: (category: Char, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var text by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // カテゴリ(A〜Z)を選ぶプルダウン。
                // 標準的な ExposedDropdownMenuBox + DropdownMenu の組み合わせ(確実に動く定番の書き方)。
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        value = previewFor(selectedCategory),
                        onValueChange = {},
                        label = { Text("カテゴリ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        ('A'..'Z').forEach { letter ->
                            DropdownMenuItem(
                                text = { Text(previewFor(letter)) },
                                onClick = {
                                    selectedCategory = letter
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("名前(空欄なら連番名のみになります)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedCategory, text.trim()) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}
