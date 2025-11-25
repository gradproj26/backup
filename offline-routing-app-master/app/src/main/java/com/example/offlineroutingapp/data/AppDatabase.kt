package com.example.offlineroutingapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.offlineroutingapp.data.dao.ChatDao
import com.example.offlineroutingapp.data.dao.MessageDao
import com.example.offlineroutingapp.data.dao.UserDao
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.data.entities.MessageEntity
import com.example.offlineroutingapp.data.entities.UserEntity

@Database(
    entities = [UserEntity::class, ChatEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "offline_chat_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}