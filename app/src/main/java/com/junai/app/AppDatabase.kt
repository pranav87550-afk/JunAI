package com.junai.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.junai.app.memory.MemoryDao
import com.junai.app.memory.MemoryEntity

@Database(
    entities = [
        KnowledgeEntity::class,
        KnowledgeItem::class,
        CommandItem::class,
        SkillItem::class,
        AliasItem::class,
        RelatedQuestionItem::class,
        FailureLog::class,
        LearningItem::class,
        LearningStatistics::class,
        MemoryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun learningDao(): LearningDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE knowledge ADD COLUMN aliases TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE knowledge ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE knowledge ADD COLUMN timesAsked INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE knowledge ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Learning Items
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS learning_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        question TEXT NOT NULL,
                        detectedIntent TEXT NOT NULL DEFAULT 'UNKNOWN',
                        suggestedIntent TEXT NOT NULL DEFAULT '',
                        suggestedCategory TEXT NOT NULL DEFAULT '',
                        confidence REAL NOT NULL DEFAULT 0,
                        failureReason TEXT NOT NULL DEFAULT 'NO_MATCH',
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        dateAdded INTEGER NOT NULL,
                        lastUpdated INTEGER NOT NULL
                    )
                """)

                // Knowledge Items
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS knowledge_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        question TEXT NOT NULL,
                        answer TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'General',
                        confidence REAL NOT NULL DEFAULT 1.0,
                        timesAsked INTEGER NOT NULL DEFAULT 0,
                        timesCorrect INTEGER NOT NULL DEFAULT 0,
                        dateAdded INTEGER NOT NULL,
                        lastUpdated INTEGER NOT NULL
                    )
                """)

                // Command Items
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS command_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        phrase TEXT NOT NULL,
                        intent TEXT NOT NULL,
                        target TEXT NOT NULL DEFAULT '',
                        category TEXT NOT NULL DEFAULT 'GENERAL',
                        confidence REAL NOT NULL DEFAULT 1.0,
                        timesUsed INTEGER NOT NULL DEFAULT 0,
                        dateAdded INTEGER NOT NULL
                    )
                """)

                // Skill Items
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS skill_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        phrase TEXT NOT NULL,
                        skillType TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        isActive INTEGER NOT NULL DEFAULT 1,
                        dateAdded INTEGER NOT NULL
                    )
                """)

                // Alias Items
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS alias_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        knowledgeId INTEGER NOT NULL,
                        alias TEXT NOT NULL,
                        dateAdded INTEGER NOT NULL
                    )
                """)

                // Related Questions
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS related_questions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        questionId INTEGER NOT NULL,
                        relatedQuestion TEXT NOT NULL,
                        relationshipType TEXT NOT NULL DEFAULT 'RELATED',
                        dateAdded INTEGER NOT NULL
                    )
                """)

                // Failure Log
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS failure_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        question TEXT NOT NULL,
                        detectedIntent TEXT NOT NULL DEFAULT 'UNKNOWN',
                        confidence REAL NOT NULL DEFAULT 0,
                        failureReason TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """)

                // Learning Statistics
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS learning_statistics (
                        id INTEGER PRIMARY KEY NOT NULL,
                        knowledgeLearned INTEGER NOT NULL DEFAULT 0,
                        commandsLearned INTEGER NOT NULL DEFAULT 0,
                        skillsLearned INTEGER NOT NULL DEFAULT 0,
                        totalIntents INTEGER NOT NULL DEFAULT 0,
                        failedQueries INTEGER NOT NULL DEFAULT 0,
                        totalQueries INTEGER NOT NULL DEFAULT 0,
                        lastUpdated INTEGER NOT NULL
                    )
                """)

                // Insert default statistics row
                database.execSQL("""
                    INSERT OR IGNORE INTO learning_statistics 
                    (id, knowledgeLearned, commandsLearned, skillsLearned, totalIntents, failedQueries, totalQueries, lastUpdated)
                    VALUES (1, 0, 0, 0, 0, 0, 0, ${System.currentTimeMillis()})
                """)
            }
        }

        // NEW — Phase 1: Hybrid Memory System
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        summary TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'GENERAL',
                        source TEXT NOT NULL DEFAULT 'CONVERSATION',
                        memoryType TEXT NOT NULL DEFAULT 'SHORT_TERM',
                        importance REAL NOT NULL DEFAULT 0.3,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        tags TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL,
                        lastAccessed INTEGER NOT NULL,
                        accessCount INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "junai_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
