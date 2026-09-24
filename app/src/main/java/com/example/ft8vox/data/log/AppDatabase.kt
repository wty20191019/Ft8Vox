package com.example.ft8vox.data.log

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Ft8Vox 本地数据库（当前仅通联日志）。 */
@Database(entities = [QsoEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun qsoDao(): QsoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ft8vox.db",
                ).build().also { instance = it }
            }
    }
}
