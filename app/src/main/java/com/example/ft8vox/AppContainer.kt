package com.example.ft8vox

import android.app.Application
import android.content.Context
import com.example.ft8vox.data.log.AppDatabase
import com.example.ft8vox.data.log.QsoRepository
import com.example.ft8vox.data.settings.SettingsRepository

/**
 * 手工依赖容器（不引入 DI 框架）。
 *
 * 在 [Ft8VoxApplication] 中懒加载，ViewModel 通过 `application.container` 取用；
 * 所有仓库共享同一份 DataStore / Room 实例。
 */
class AppContainer(context: Context) {
    val settings: SettingsRepository = SettingsRepository(context)
    val qso: QsoRepository = QsoRepository(AppDatabase.get(context).qsoDao())
}

/** 应用入口：持有全局依赖容器。 */
class Ft8VoxApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** 从任意 [Application] 取容器（实际类型在 Manifest 中固定为 [Ft8VoxApplication]）。 */
val Application.container: AppContainer
    get() = (this as Ft8VoxApplication).container
