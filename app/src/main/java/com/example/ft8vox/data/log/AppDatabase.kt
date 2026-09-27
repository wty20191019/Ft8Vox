package com.example.ft8vox.data.log

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Ft8Vox 本地数据库（当前仅通联日志）。 */
@Database(entities = [QsoEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun qsoDao(): QsoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：通联日志补齐 FT8CN 的**起始时间**字段（ADIF 的 `QSO_DATE` / `TIME_ON`）。
         *
         * 旧记录（v1）没有起始时间，默认 `0` 表示未知，导出 ADIF 时回退为完成时间
         * （`TIME_ON` = `TIME_OFF`），因此升级不会改变既有日志的导出内容。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qso ADD COLUMN startUtcMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ft8vox.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
