package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VmInstanceEntity::class,
        VmSandboxFileEntity::class,
        GuestInstalledAppEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vmDao(): VmDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kestrel_vm_database.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
