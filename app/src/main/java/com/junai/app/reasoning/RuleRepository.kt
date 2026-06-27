package com.junai.app.reasoning

import android.content.Context
import android.os.BatteryManager
import java.util.Calendar

/**
 * RuleRepository — Android-aware bridge between real device state and the
 * pure ReasoningEngine. Builds a ReasoningContext from live data (battery%,
 * charging, current hour) and holds the starter rule set.
 *
 * Starter rules are intentionally limited to signals the app can actually
 * read right now (battery, time) — no "is user outside" rule, since there's
 * no location/sensor code in the project to back that honestly.
 */
class RuleRepository(private val context: Context) {

    private val rules: List<ReasoningRule> = listOf(
        ReasoningRule(
            id = "BATTERY_CRITICAL",
            condition = ReasoningCondition.And(
                ReasoningCondition.LessThan("battery", 5f),
                ReasoningCondition.Equals("charging", "false")
            ),
            recommendationText = "Battery 5% se kam hai aur charging nahi ho rahi — charger lagana zaroori hai! \u26A1",
            priority = 100
        ),
        ReasoningRule(
            id = "BATTERY_LOW",
            condition = ReasoningCondition.And(
                ReasoningCondition.LessThan("battery", 15f),
                ReasoningCondition.Equals("charging", "false")
            ),
            recommendationText = "Battery 15% se kam hai — Battery Saver on kar lo? \uD83D\uDD0B",
            priority = 50
        ),
        ReasoningRule(
            id = "BATTERY_FULL_CHARGING",
            condition = ReasoningCondition.And(
                ReasoningCondition.GreaterThan("battery", 95f),
                ReasoningCondition.Equals("charging", "true")
            ),
            recommendationText = "Battery almost full hai — charger nikal sakte ho, battery health ke liye accha rahega \uD83D\uDD0C",
            priority = 30
        ),
        ReasoningRule(
            id = "LATE_NIGHT",
            condition = ReasoningCondition.HourBetween(23, 5),
            recommendationText = "Raat ho gayi hai — thoda rest bhi zaroori hai \uD83C\uDF19",
            priority = 10
        )
    )

    /** Builds a fresh context from live device state. */
    fun buildContext(): ReasoningContext {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        return ReasoningContext.Builder()
            .putNumber("battery", level.toFloat())
            .putNumber("hour", hour.toFloat())
            .putString("charging", charging.toString())
            .build()
    }

    /** Evaluates starter rules against current device state, returns top recommendation if any. */
    fun getRecommendation(): ReasoningRule? {
        return ReasoningEngine.evaluate(rules, buildContext())
    }

    fun getAllRules(): List<ReasoningRule> = rules
}
