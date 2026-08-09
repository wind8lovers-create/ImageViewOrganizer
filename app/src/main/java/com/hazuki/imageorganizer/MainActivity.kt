package com.hazuki.imageorganizer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hazuki.imageorganizer.ui.screens.MainScreen
import com.hazuki.imageorganizer.ui.theme.ImageViewOrganizerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ステータスバー(時計・電波・バッテリー)のアイコンを、常に白色で表示するよう固定する。
        // TopBar側でステータスバーの高さ分を黒帯として塗るので、白アイコンなら常にはっきり見える。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            ImageViewOrganizerTheme {
                MainScreen()
            }
        }
    }
}
