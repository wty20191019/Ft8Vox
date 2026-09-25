package com.example.ft8vox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.ft8vox.data.settings.ThemeMode
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
            val app by settings.settings.collectAsState()
            Ft8VoxTheme(
                darkTheme = app.themeMode == ThemeMode.DARK,
                fontScale = app.fontSize.scale,
            ) {
                MainShell(session = session, log = log, settings = settings)
            }
        }
    }

    override fun onStop() {
        // 后台不留驻采音（Android 14 起后台麦克风需前台服务，阶段 9 再上前台服务）
        session.stop()
        super.onStop()
    }
}
