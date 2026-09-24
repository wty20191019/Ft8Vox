package com.example.ft8vox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ft8vox.container
import com.example.ft8vox.data.log.QsoEntity
import com.example.ft8vox.data.log.QsoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 日志页状态；筛选与统计的纯逻辑在 [LogQuery]。 */
class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.container.qso

    /** 全部记录（新→旧）。 */
    val entries: StateFlow<List<QsoEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _filter = MutableStateFlow(LogFilter())
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    /** 应用筛选后的记录。 */
    val filtered: StateFlow<List<QsoEntity>> = combine(entries, _filter) { list, f ->
        LogQuery.applyFilter(list, f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val stats: StateFlow<LogStats> = entries.map { LogQuery.computeStats(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LogStats())

    fun setFilter(filter: LogFilter) {
        _filter.value = filter
    }

    fun add(entity: QsoEntity) {
        viewModelScope.launch { repo.add(entity) }
    }

    fun update(entity: QsoEntity) {
        viewModelScope.launch { repo.update(entity) }
    }

    fun delete(entity: QsoEntity) {
        viewModelScope.launch { repo.delete(entity) }
    }

    fun clearAll(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.clear()
            onDone()
        }
    }

    suspend fun importAdif(text: String, myCall: String, myGrid: String?): QsoRepository.ImportResult =
        repo.importAdif(text, myCall, myGrid)

    suspend fun exportAdif(): String = repo.exportAdif()
}
