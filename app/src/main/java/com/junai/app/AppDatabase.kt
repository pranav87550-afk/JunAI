package com.junai.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.junai.app.agent.AgentTaskDao
import com.junai.app.agent.AgentTaskEntity
import com.junai.app.agent.memory.AgentMemoryDao
import com.junai.app.agent.memory.AgentMemoryEntity
import com.junai.app.learning.RecordedMacroDao
import com.junai.app.learning.RecordedMacroEntity
import com.junai.app.learning.LearnedElementDao
import com.junai.app.learning.LearnedElementEntity
import com.junai.app.learning.StepOutcomeDao
import com.junai.app.learning.StepOutcomeEntity
import com.junai.app.memory.GraphEdgeEntity
import com.junai.app.memory.GraphNodeEntity
import com.junai.app.memory.KnowledgeGraphDao
import com.junai.app.memory.MemoryDao
import com.junai.app.memory.MemoryEntity
import com.junai.app.memory.SemanticFactDao
import com.junai.app.memory.SemanticFactEntity
import com.junai.app.planning.PlanDao
import com.junai.app.planning.PlanEntity
import com.junai.app.planning.PlanStepEntity
import com.junai.app.reasoning.ReflectionDao
import com.junai.app.reasoning.ReflectionEntity
import com.junai.app.passive.AppLearningPermissionDao
import com.junai.app.passive.AppLearningPermissionEntity
import com.junai.app.passive.PassiveScreenDao
import com.junai.app.passive.PassiveScreenEntity
import com.junai.app.passive.PassiveElementDao
import com.junai.app.passive.PassiveElementEntity
import com.junai.app.passive.PassiveEdgeDao
import com.junai.app.passive.PassiveEdgeEntity

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
        ReflectionEntity::class,
        PlanEntity::class,
        PlanStepEntity::class,
        AgentMemoryEntity::class,
        AgentTaskEntity::class,
        RecordedMacroEntity::class,
        LearnedElementEntity::class,
        StepOutcomeEntity::class,
        AppLearningPermissionEntity::class,
        PassiveScreenEntity::class,
        PassiveElementEntity::class,
        PassiveEdgeEntity::class
    ],
    version = 17,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun learningDao(): LearningDao
    abstract fun memoryDao(): MemoryDao
    abstract fun semanticFactDao(): SemanticFactDao
    abstract fun knowledgeGraphDao(): KnowledgeGraphDao
    abstract fun reflectionDao(): ReflectionDao
    abstract fun planDao(): PlanDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun recordedMacroDao(): RecordedMacroDao
    abstract fun learnedElementDao(): LearnedElementDao
    abstract fun stepOutcomeDao(): StepOutcomeDao
    abstract fun appLearningPermissionDao(): AppLearningPermissionDao
    abstract fun passiveScreenDao(): PassiveScreenDao
    abstract fun passiveElementDao(): PassiveElementDao
    abstract fun passiveEdgeDao(): PassiveEdgeDao

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

        // NEW — Phase 10: Planner
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS plans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        goalText TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS plan_steps (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        planId INTEGER NOT NULL,
                        stepText TEXT NOT NULL,
                        stepOrder INTEGER NOT NULL,
                        isCompleted INTEGER NOT NULL DEFAULT 0,
                        completedAt INTEGER
                    )
                """)
            }
        }

        // NEW — Phase 15: Agent Memory
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_memory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        goalText TEXT NOT NULL,
                        wasSuccessful INTEGER NOT NULL,
                        stepsUsed TEXT NOT NULL,
                        toolsUsed TEXT NOT NULL,
                        preferredApps TEXT NOT NULL,
                        failureReason TEXT,
                        improvementNote TEXT,
                        timestamp INTEGER NOT NULL
                    )
                """)
            }
        }

        // NEW — Phase 15: Multi-Step Task Manager
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        goalText TEXT NOT NULL,
                        agentTaskParams TEXT NOT NULL,
                        steps TEXT NOT NULL,
                        currentStep INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """)
            }
        }

        // NEW — Phase 16: Learned Macros (record & replay via "Execute")
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS recorded_macros (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        triggerPhrase TEXT NOT NULL,
                        displayPhrase TEXT NOT NULL,
                        stepsJson TEXT NOT NULL,
                        stepCount INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL DEFAULT 0,
                        timesReplayed INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // NEW — Phase 17: persistent cross-macro element knowledge base
        // (size, label, interaction count) — see LearnedElementEntity doc.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS learned_elements (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        resourceId TEXT,
                        className TEXT,
                        label TEXT,
                        boundsLeft INTEGER NOT NULL,
                        boundsTop INTEGER NOT NULL,
                        boundsRight INTEGER NOT NULL,
                        boundsBottom INTEGER NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        interactionCount INTEGER NOT NULL DEFAULT 1,
                        firstSeenAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_learned_elements_package ON learned_elements(packageName)")
            }
        }

        // IMPROVEMENT (Phase 1g): backs the needsCorrection flag on
        // KnowledgeItem — see that field's doc comment for why. Defaults
        // every existing row to 0 (false), which is correct: nothing
        // already in the table was mid-correction before this shipped.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE knowledge_items ADD COLUMN needsCorrection INTEGER NOT NULL DEFAULT 0")
            }
        }

        // NEW — Phase 4 (improvement sprint): per-step replay outcome log,
        // see StepOutcomeEntity's doc comment.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS step_outcomes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        macroId INTEGER NOT NULL,
                        stepIndex INTEGER NOT NULL,
                        actionType TEXT NOT NULL,
                        success INTEGER NOT NULL,
                        matchedVia TEXT,
                        usedAlternate INTEGER NOT NULL DEFAULT 0,
                        failureReason TEXT,
                        timestamp INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_step_outcomes_macroId ON step_outcomes(macroId)")
            }
        }

        // NEW — Passive Learning Phase 1: permissions gate for the
        // "Screen Reading" tab. Every Phase 2 capture check reads this
        // table first; default is Deny (no row = not allowed).
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_learning_permissions (
                        packageName TEXT PRIMARY KEY NOT NULL,
                        allowed INTEGER NOT NULL,
                        allowedAt INTEGER NOT NULL,
                        category TEXT
                    )
                """)
            }
        }

        // NEW — Passive Learning Phase 2: graph-shaped capture storage
        // (screens/elements/edges), built now per the Phase 2 design
        // check-in rather than a flat log converted later.
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS passive_screens (
                        screenId TEXT PRIMARY KEY NOT NULL,
                        packageName TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        firstSeenAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        observationCount INTEGER NOT NULL
                    )
                """)
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS passive_elements (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        screenId TEXT NOT NULL,
                        resourceId TEXT,
                        text TEXT,
                        contentDescription TEXT,
                        stateDescription TEXT,
                        className TEXT,
                        boundsLeft INTEGER NOT NULL,
                        boundsTop INTEGER NOT NULL,
                        boundsRight INTEGER NOT NULL,
                        boundsBottom INTEGER NOT NULL,
                        clickable INTEGER NOT NULL,
                        scrollable INTEGER NOT NULL,
                        editable INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_passive_elements_screenId ON passive_elements(screenId)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS passive_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        fromScreenId TEXT NOT NULL,
                        elementIdentifier TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        toScreenId TEXT,
                        observedCount INTEGER NOT NULL,
                        confidence INTEGER NOT NULL,
                        lastObservedAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_passive_edges_fromScreenId ON passive_edges(fromScreenId)")
            }
        }

        // NEW — Passive Learning Phase 3: track which app version a
        // learned screen/edge was last confirmed against, so a layout
        // update doesn't get silently misapplied.
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE passive_screens ADD COLUMN appVersion INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE passive_edges ADD COLUMN appVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "junai_database"
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
