package com.example.ft8vox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.ui.theme.Ft8VoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ft8VoxTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 阶段 1：验证 JNI 与 libft8.so 的加载链路
                    val engineStatus = remember { Ft8Engine.test() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = engineStatus,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}
