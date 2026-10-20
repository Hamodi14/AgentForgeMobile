package com.agentforge.mobile.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class RoomConverters {
    @TypeConverter
    fun fromTaskStatus(status: TaskRunStatus): String = status.name

    @TypeConverter
    fun toTaskStatus(value: String): TaskRunStatus =
        runCatching { TaskRunStatus.valueOf(value) }.getOrDefault(TaskRunStatus.IDLE)
}

@Database(
    entities = [
        ProjectEntity::class,
        TaskSessionEntity::class,
        ChatMessageEntity::class,
        ExecutionLogEntity::class,
        FileBackupEntity::class,
        ProviderConfigEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AgentForgeDatabase : RoomDatabase() {
    abstract fun dao(): AgentForgeDao

    companion object {
        @Volatile
        private var INSTANCE: AgentForgeDatabase? = null

        fun getInstance(context: Context): AgentForgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentForgeDatabase::class.java,
                    "agentforge_mobile.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
