package com.junai.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.junai.app.memory.GraphEdgeEntity
import com.junai.app.memory.GraphNodeEntity
import com.junai.app.memory.KnowledgeGraphDao
import com.junai.app.memory.MemoryDao
import com.junai.app.memory.MemoryEntity
import com.junai.app.memory.SemanticFactDao
import com.junai.app.memory.SemanticFactEntity
import com.junai.app.reasoning.ReflectionDao
import com.junai.app.reasoning.ReflectionEntity

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
        MemoryEntity::class,
        SemanticFactEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
        ReflectionEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun learningDao(): LearningDao
    abstract fun memoryDao(): MemoryDao
    abstract fun semanticFactDao(): SemanticFactDao
    abstract fun knowledgeGraphDao(): KnowledgeGraphDao
    abstract fun reflectionDao(): ReflectionDao

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

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS alias_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        knowledgeId INTEGER NOT NULL,
                        alias TEXT NOT NULL,
                        dateAdded INTEGER NOT NULL
                    )
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS related_questions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        questionId INTEGER NOT NULL,
                        relatedQuestion TEXT NOT NULL,
                        relationshipType TEXT NOT NULL DEFAULT 'RELATED',
                        dateAdded INTEGER NOT NULL
                    )
                """)

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

                database.execSQL("""
                    INSERT OR IGNORE INTO learning_statistics 
                    (id, knowledgeLearned, commandsLearned, skillsLearned, totalIntents, failedQueries, totalQueries, lastUpdated)
                    VALUES (1, 0, 0, 0, 0, 0, 0, ${System.currentTimeMillis()})
                """)
            }
        }

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS semantic_facts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        subject TEXT NOT NULL DEFAULT 'USER',
                        predicate TEXT NOT NULL,
                        objectValue TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'GENERAL',
                        confidence REAL NOT NULL DEFAULT 0.8,
                        sourceText TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS graph_nodes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'CONCEPT',
                        timestamp INTEGER NOT NULL
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS graph_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fromNodeId INTEGER NOT NULL,
                        relation TEXT NOT NULL,
                        toNodeId INTEGER NOT NULL,
                        confidence REAL NOT NULL DEFAULT 0.8,
                        sourceText TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL
                    )
                """)
            }
        }

        // NEW — Phase 7: Reflection Engine
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS reflection_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        learnedSummary TEXT NOT NULL,
                        failureSummary TEXT NOT NULL,
                        patternsSummary TEXT NOT NULL,
                        improvementSuggestion TEXT NOT NULL,
                        factsCountSnapshot INTEGER NOT NULL DEFAULT 0,
                        edgesCountSnapshot INTEGER NOT NULL DEFAULT 0,
                        totalQueriesSnapshot INTEGER NOT NULL DEFAULT 0,
                        failedQueriesSnapshot INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
