package com.hazuki.imageorganizer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hazuki.imageorganizer.data.SortOption
import com.hazuki.imageorganizer.ui.theme.FujiPrimaryDark
import com.hazuki.imageorganizer.ui.theme.FujiSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortBottomSheet(
    currentSort: SortOption,
    sheetState: SheetState,
    onSelect: (SortOption) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = FujiSurface
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "並び替え",
                fontWeight = FontWeight.Bold,
                color = FujiPrimaryDark,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            // ユーザー選択可能なソート項目（visibleValues）のみを一覧表示
            SortOption.visibleValues.forEach { option ->
                Text(
                    text = option.label,
                    color = if (option == currentSort) FujiPrimaryDark else FujiPrimaryDark.copy(alpha = 0.7f),
                    fontWeight = if (option == currentSort) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }
    }
}
