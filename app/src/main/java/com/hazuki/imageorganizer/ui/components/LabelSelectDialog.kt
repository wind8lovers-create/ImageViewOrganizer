package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * =====================================================================
 * 【ラベル選択ダイアログ】
 * 
 * 機能：
 * - TopBar の中央に配置される「□▷」ボタンをタップで表示
 * - ラベル一覧（約23種類）を縦リスト表示
 * - ラベルをタップすると即座に選択＆ダイアログ自動クローズ
 * - キャンセル時は前回選択したラベルは変わらない
 * 
 * 表示方法：
 * - DropdownMenu を使用（既存の SelectionMenuButton と同じスタイル）
 * - 1行1ラベル
 * - スクロール対応（23種類は縦幅で表示可能）
 * =====================================================================
 */
@Composable
fun LabelSelectDialog(
    // ---- ラベル関連情報 ----
    labels: List<String>,                    // assets/labels.txt から読み込んだラベル一覧
    currentLabel: String,                    // 現在選択中のラベル（初期値）
    onLabelSelected: (String) -> Unit,       // ラベル選択時のコールバック
    
    modifier: Modifier = Modifier
) {
    // =====================================
    // 【状態管理】
    // =====================================
    
    // ドロップダウンメニューの表示/非表示を管理
    var showMenu by remember { mutableStateOf(false) }
    
    // =====================================
    // 【UI】
    // =====================================
    
    Box {
        // 「□▷」ボタン（トリガー：タップでラベル一覧ドロップダウンを表示）
        Box(
            modifier = modifier
                .clickable { showMenu = !showMenu }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "□▷",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        // ドロップダウンメニュー（ラベル一覧）
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }  // メニュー外をタップで閉じる
        ) {
            // =====================================
            // 【ラベルリスト】約23種類を縦リスト表示
            // =====================================
            labels.forEach { label ->
                DropdownMenuItem(
                    text = {
                        // ラベル名をテキスト表示
                        Text(
                            text = label,
                            fontSize = 14.sp,
                            fontWeight = if (label == currentLabel) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        // =====================================
                        // 【ラベル選択時の処理】
                        // =====================================
                        
                        // 1. 選択したラベルをコールバック経由で ViewMode に通知
                        onLabelSelected(label)
                        
                        // 2. ドロップダウンメニューを即座に閉じる
                        showMenu = false
                    },
                    
                    // 現在選択中のラベルは強調表示（オプション）
                    leadingIcon = if (label == currentLabel) {
                        {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowRight,
                                contentDescription = "選択中",
                                tint = Color.Blue
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}
