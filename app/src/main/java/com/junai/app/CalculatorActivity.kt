package com.junai.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CalculatorActivity : AppCompatActivity() {

    private var expression = ""
    private var result = "0"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        val expressionText = findViewById<TextView>(R.id.expressionText)
        val resultText = findViewById<TextView>(R.id.resultText)

        fun updateDisplay() {
            expressionText.text = expression
            resultText.text = result
        }

        fun appendToExpression(value: String) {
            expression += value
            updateDisplay()
        }

        findViewById<Button>(R.id.btnAC).setOnClickListener {
            expression = ""
            result = "0"
            updateDisplay()
        }

        findViewById<Button>(R.id.btnDel).setOnClickListener {
            if (expression.isNotEmpty()) {
                expression = expression.dropLast(1)
                updateDisplay()
            }
        }

        findViewById<Button>(R.id.btnPercent).setOnClickListener { appendToExpression("%") }
        findViewById<Button>(R.id.btnDiv).setOnClickListener { appendToExpression("÷") }
        findViewById<Button>(R.id.btnMul).setOnClickListener { appendToExpression("×") }
        findViewById<Button>(R.id.btnSub).setOnClickListener { appendToExpression("−") }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { appendToExpression("+") }
        findViewById<Button>(R.id.btnDot).setOnClickListener { appendToExpression(".") }
        findViewById<Button>(R.id.btn0).setOnClickListener { appendToExpression("0") }
        findViewById<Button>(R.id.btn00).setOnClickListener { appendToExpression("00") }
        findViewById<Button>(R.id.btn1).setOnClickListener { appendToExpression("1") }
        findViewById<Button>(R.id.btn2).setOnClickListener { appendToExpression("2") }
        findViewById<Button>(R.id.btn3).setOnClickListener { appendToExpression("3") }
        findViewById<Button>(R.id.btn4).setOnClickListener { appendToExpression("4") }
        findViewById<Button>(R.id.btn5).setOnClickListener { appendToExpression("5") }
        findViewById<Button>(R.id.btn6).setOnClickListener { appendToExpression("6") }
        findViewById<Button>(R.id.btn7).setOnClickListener { appendToExpression("7") }
        findViewById<Button>(R.id.btn8).setOnClickListener { appendToExpression("8") }
        findViewById<Button>(R.id.btn9).setOnClickListener { appendToExpression("9") }

        findViewById<Button>(R.id.btnEquals).setOnClickListener {
            try {
                val expr = expression
                    .replace("÷", "/")
                    .replace("×", "*")
                    .replace("−", "-")
                    .replace("%", "/100")
                val res = eval(expr)
                result = if (res.isInfinite() || res.isNaN()) "Error"
                         else if (res % 1.0 == 0.0 && res < Long.MAX_VALUE) res.toLong().toString()
                         else "%.10g".format(res).trimEnd('0').trimEnd('.')
                expression = result
                updateDisplay()
            } catch (e: Exception) {
    result = when {
        expression.contains("/0") -> "Can't divide by zero! ❌"
        expression.isEmpty() -> "0"
        else -> "Error ❌"
    }
    updateDisplay()
            }
        }
    }

    private fun eval(expr: String): Double {
        val tokens = expr.trim()
        return object : Any() {
            var pos = -1
            var ch = 0

            fun nextChar() {
                ch = if (++pos < tokens.length) tokens[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) { nextChar(); return true }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < tokens.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    x = when {
                        eat('+'.code) -> x + parseTerm()
                        eat('-'.code) -> x - parseTerm()
                        else -> return x
                    }
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    x = when {
                        eat('*'.code) -> x * parseFactor()
                        eat('/'.code) -> {
                            val divisor = parseFactor()
                            if (divisor == 0.0) throw RuntimeException("Division by zero")
                            x / divisor
                        }
                        else -> return x
                    }
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor()
                if (eat('-'.code)) return -parseFactor()
                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) {
                    while (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) nextChar()
                    x = tokens.substring(startPos, pos).toBigDecimal().toDouble()
                } else {
                    throw RuntimeException("Unexpected: " + ch.toChar())
                }
                if (eat('^'.code)) x = Math.pow(x, parseFactor())
                return x
            }
        }.parse()
    }
}
