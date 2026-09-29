package com.example.ft8vox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ft8vox.ui.LogViewModel
import com.example.ft8vox.ui.MainShell
import com.example.ft8vox.ui.SessionViewModel
import com.example.ft8vox.ui.SettingsViewModel
import com.example.ft8vox.ui.theme.Ft8VoxTheme

class MainActivity : ComponentActivity() {

    private val session: SessionViewModel by viewModels()
    private val log: LogViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 新竖屏外壳固定深色、固定字号（docs/UI-MOBILE.md §1：去掉亮/暗主题与字体档位）
            Ft8VoxTheme {
                MainShell(session = session, log = log, settings = settings)
            }
        }
    }
}
