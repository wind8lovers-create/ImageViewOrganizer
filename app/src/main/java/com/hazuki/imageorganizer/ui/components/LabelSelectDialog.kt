package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
 * - 通常タップ: 作業ラベル（currentLabel）を変更
 * - 長押し: カレントフォルダ直下にそのラベルのフォルダがあれば移動
 * =====================================================================
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LabelSelectDialog(
    // ---- ラベル関連情報 ----
    labels: List<String>,                    // assets/labels.txt から読み込んだラベル一覧
    currentLabel: String,                    // 現在選択中のラベル（初期値）
    onLabelSelected: (String) -> Unit,       // 【通常タップ時】ラベル変更コールバック
    onLabelClick: (String) -> Unit = {},     // 【長押し時】サブフォルダ移動コールバック
    
    // ---- フォルダ階層移動（親フォルダへ戻る機能） ----
    canNavigateUp: Boolean = false,          // 上の階層へ戻れるかどうか（下層フォルダにいる時のみtrue）
    onNavigateUp: () -> Unit = {},           // 【「..⤴」タップ時】親フォルダへ戻るコールバック
    
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
        // ラベル選択ボタン（[ 🏷️ →📁 ]）
        Row(
            modifier = modifier
                .clickable { showMenu = !showMenu }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "[",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = Icons.Default.Label,
                contentDescription = "ラベル選択",
                // 【アイコンカラー】指定色 #FF67C6（鮮やかなピンク）
                tint = Color(0xFFFF67C6),
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .size(16.dp)
            )
            Text(
                text = "→📁]",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        // ドロップダウンメニュー（ラベル一覧）
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }  // メニュー外をタップで閉じる
        ) {
            // =================================================================
            // 【最上部】「..⤴」一階層上の親フォルダに戻るボタン
            // ・起動直後（起点フォルダ）：グレーアウト（薄い文字・タップ無効）
            // ・ラベル選択で下層へ潜った時：通常表示（ハッキリとした文字・タップ可能）
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // canNavigateUp が true の時だけタップ可能にして親フォルダへ戻る
                    .then(
                        if (canNavigateUp) {
                            Modifier.clickable {
                                showMenu = false
                                onNavigateUp()
                            }
                        } else {
                            Modifier // 無効時は clickable を付与せずタップを受け付けない
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "..⤴",
                    fontSize = 15.sp,
                    // 有効時はハッキリとした色、グレーアウト時は薄いグレー
                    color = if (canNavigateUp) FujiPrimaryDark else Color.Gray.copy(alpha = 0.4f),
                    fontWeight = FontWeight.Bold
                )
            }

            // 親フォルダ戻るボタンとラベル一覧を区別する薄い仕切り線
            androidx.compose.material3.HorizontalDivider(
                color = Color.Gray.copy(alpha = 0.2f),
                thickness = 0.5.dp
            )

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
                                // 【通常タップ】作業ラベルを変更（currentLabel をこのラベルに切り替え）
                                showMenu = false
                                onLabelSelected(label)
                            },
                            onLongClick = {
                                // 【長押し】サブフォルダ移動（直下に同名フォルダが存在すれば移動）
                                showMenu = false
                                onLabelClick(label)
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
