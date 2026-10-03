package dev.undefinedteam.wearagent.presentation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.sp

/**
 * Watch-sized markdown and formula rendering. It covers headings, lists,
 * emphasis, code, and `$...$` math. It is not a full Markdown or TeX engine.
 */
object RichText {
    fun of(source: String): AnnotatedString = buildAnnotatedString {
        var code = false
        source.lines().forEachIndexed { index, raw ->
            if (index > 0) append('\n')
            val line = raw.trimEnd()
            if (line.startsWith("```")) {
                code = !code
                return@forEachIndexed
            }
            if (code) {
                val start = length
                append(line)
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, length)
                return@forEachIndexed
            }
            val text = when {
                line.startsWith("### ") -> line.removePrefix("### ")
                line.startsWith("## ") -> line.removePrefix("## ")
                line.startsWith("# ") -> line.removePrefix("# ")
                line.startsWith("- ") -> "• ${line.removePrefix("- ")}"
                else -> line
            }
            val start = length
            appendInline(text)
            if (line.startsWith("#")) addStyle(
                SpanStyle(fontWeight = FontWeight.SemiBold),
                start,
                length
            )
        }
    }

    private fun AnnotatedString.Builder.appendInline(text: String) {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) {
                        append(text.substring(i))
                        return
                    }
                    style(SpanStyle(fontWeight = FontWeight.Bold), text.substring(i + 2, end))
                    i = end + 2
                }

                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        append(text.substring(i))
                        return
                    }
                    style(SpanStyle(fontFamily = FontFamily.Monospace), text.substring(i + 1, end))
                    i = end + 1
                }

                text.startsWith("\$", i) -> {
                    val end = text.indexOf('$', i + 1)
                    if (end < 0) {
                        append(text.substring(i))
                        return
                    }
                    appendFormula(text.substring(i + 1, end))
                    i = end + 1
                }

                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end < 0) {
                        append(text.substring(i))
                        return
                    }
                    style(SpanStyle(fontStyle = FontStyle.Italic), text.substring(i + 1, end))
                    i = end + 1
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendFormula(raw: String) {
        val text = expandFormula(raw)
        var i = 0
        while (i < text.length) {
            val mark = text[i]
            if ((mark == '^' || mark == '_') && i + 1 < text.length) {
                val script = if (text[i + 1] == '{') {
                    val end = text.indexOf('}', i + 2)
                    if (end < 0) text.substring(i + 1) else text.substring(i + 2, end)
                } else {
                    text[i + 1].toString()
                }
                val shift = if (mark == '^') BaselineShift.Superscript else BaselineShift.Subscript
                style(SpanStyle(baselineShift = shift, fontSize = 11.sp), script)
                i += if (text[i + 1] == '{') script.length + 3 else 2
            } else {
                append(mark)
                i++
            }
        }
    }

    private fun AnnotatedString.Builder.style(span: SpanStyle, value: String) {
        val start = length
        append(value)
        addStyle(span, start, length)
    }

    private fun expandFormula(raw: String): String {
        var text = raw
        val names = listOf(
            "\\times" to "×", "\\cdot" to "·", "\\leq" to "≤", "\\geq" to "≥",
            "\\neq" to "≠", "\\pm" to "±", "\\pi" to "π", "\\alpha" to "α",
            "\\beta" to "β", "\\gamma" to "γ", "\\theta" to "θ", "\\lambda" to "λ",
            "\\infty" to "∞",
        )
        names.forEach { (from, to) -> text = text.replace(from, to) }
        text = replaceAll(text, "\\frac") { args -> "(${args[0]})/(${args[1]})" }
        text = replaceAll(text, "\\sqrt") { args -> "√(${args[0]})" }
        return text.replace("{", "").replace("}", "")
    }

    private fun replaceAll(
        text: String,
        command: String,
        format: (List<String>) -> String
    ): String {
        var current = text
        while (current.contains(command)) {
            val next = replaceCommand(current, command, format)
            if (next == current) break
            current = next
        }
        return current
    }

    private fun replaceCommand(
        text: String,
        command: String,
        format: (List<String>) -> String
    ): String {
        val start = text.indexOf(command)
        if (start < 0) return text
        val args = ArrayList<String>()
        var i = start + command.length
        repeat(if (command == "\\sqrt") 1 else 2) {
            if (i >= text.length || text[i] != '{') return text
            val end = text.indexOf('}', i + 1)
            if (end < 0) return text
            args.add(text.substring(i + 1, end))
            i = end + 1
        }
        return text.replaceRange(start, i, format(args))
    }
}
