package com.example.ft8vox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ft8vox.container
import com.example.ft8vox.data.settings.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页的状态与写入入口（DataStore 为唯一事实来源）。 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.container.settings

    /** 全部设置；初始值为默认值，随后由 DataStore 覆盖。 */
    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** 读-改-写；调用方无需关心协程。 */
    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    /** 恢复默认（保留台站信息）。 */
    fun resetToDefaults() {
        viewModelScope.launch {
            repo.update { current ->
                AppSettings(myCall = current.myCall, myGrid = current.myGrid, note = current.note)
            }
        }
    }
}
