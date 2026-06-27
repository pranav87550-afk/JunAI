package com.junai.app.reasoning

/**
 * ReasoningContext — A snapshot of current facts the ReasoningEngine
 * evaluates conditions against (battery%, charging, hour of day, etc).
 *
 * Kept Android-free on purpose (no Activity/Context dependency) so
 * ReasoningCondition/ReasoningEngine stay pure and easy to test.
 * The actual data (BatteryManager reads, current hour) gets collected
 * and put into this by RuleRepository, which does have Android access.
 */
class ReasoningContext private constructor(
    private val numbers: Map<String, Float>,
    private val strings: Map<String, String>
) {
    fun getNumber(key: String): Float? = numbers[key]
    fun getString(key: String): String? = strings[key]

    class Builder {
        private val numbers = mutableMapOf<String, Float>()
        private val strings = mutableMapOf<String, String>()

        fun putNumber(key: String, value: Float): Builder {
            numbers[key] = value
            return this
        }

        fun putString(key: String, value: String): Builder {
            strings[key] = value
            return this
        }

        fun build(): ReasoningContext = ReasoningContext(numbers, strings)
    }
}
