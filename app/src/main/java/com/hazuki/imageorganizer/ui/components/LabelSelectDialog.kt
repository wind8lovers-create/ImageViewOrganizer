package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.DropdownMenu
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
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark

/**
 * =====================================================================
 * 【ラベル選択ダイアログ】
 * 
 * 機能：
 * - TopBar の中央に配置されるタグアイコンボタンをタップで表示
 * - 通常タップ: カレントフォルダ直下にそのラベルのフォルダがあれば移動
 * - 長押し: 作業ラベル（currentLabel）を変更
 * =====================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LabelSelectDialog(
    // ---- ラベル関連情報 ----
    labels: List<String>,                    // assets/labels.txt から読み込んだラベル一覧
    currentLabel: String,                    // 現在選択中のラベル（初期値）
    onLabelSelected: (String) -> Unit,       // 【長押し時】ラベル変更コールバック
    onLabelClick: (String) -> Unit = {},     // 【通常タップ時】サブフォルダ移動コールバック
    
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
        // ラベル選択ボタン（トリガー：タップでラベル一覧ドロップダウンを表示）
        Box(
            modifier = modifier
                .clickable { showMenu = !showMenu }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Label,
                contentDescription = "ラベル選択",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        
        // ドロップダウンメニュー（ラベル一覧）
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }  // メニュー外をタップで閉じる
        ) {
            // 操作ヒント表示（上下2行）
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "💡 タップ→フォルダへ",
                    fontSize = 11.sp,
                    color = FujiPrimaryDark.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "　 長押し: ラベル変更",
                    fontSize = 11.sp,
                    color = FujiPrimaryDark.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Medium
                )
            }

            // =====================================
            // 【ラベルリスト】約23種類を縦リスト表示
            // =====================================
            labels.forEach { label ->
                val isSelected = label == currentLabel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                // 通常タップ: サブフォルダ移動試行
                                showMenu = false
                                onLabelClick(label)
                            },
                            onLongClick = {
                                // 長押し: ラベル名変更
                                showMenu = false
                                onLabelSelected(label)
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowRight,
                            contentDescription = "選択中",
                            tint = FujiPrimaryDark,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        color = if (isSelected) FujiPrimaryDark else Color.Unspecified,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
