package com.hazuki.imageorganizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hazuki.imageorganizer.ui.screens.MainScreen
import com.hazuki.imageorganizer.ui.theme.ImageViewOrganizerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImageViewOrganizerTheme {
                MainScreen()
            }
        }
    }
}
