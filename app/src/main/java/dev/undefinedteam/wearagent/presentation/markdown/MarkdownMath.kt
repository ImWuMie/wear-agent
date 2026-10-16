package dev.undefinedteam.wearagent.presentation.markdown

/** Math is cut out of the source before markdown runs, so LaTeX `\\`, `\{`, `\_` survive verbatim. */
internal class MathSpan(val source: String, val block: Boolean)

private const val MARK_OPEN = '\uE000'
private const val MARK_CLOSE = '\uE001'

/** Placeholder left in the markdown text where a [MathSpan] used to be. */
internal val markerPattern = Regex("\uE000(\\d+)\uE001")

/**
 * Replaces every math span with an opaque marker before markdown parsing, so markdown escaping
 * cannot touch LaTeX internals. Handles `$$..$$`, `$..$`, `\[..\]`, `\(..\)`.
 *
 * Fences and inline code are copied verbatim: a literal `$` or `\(` inside code is not a formula,
 * and pairing its opening delimiter with a later real one would swallow live text.
 */
internal fun extractMath(source: String): Pair<String, List<MathSpan>> {
    val spans = ArrayList<MathSpan>()
    val out = StringBuilder(source.length)
    var i = 0
    var fenceChar = '\u0000'
    var fenceLength = 0
    var lineStart = true

    fun mark(span: MathSpan): String {
        spans.add(span)
        return "$MARK_OPEN${spans.lastIndex}$MARK_CLOSE"
    }

    /** Copies [from]..[to] verbatim, so code keeps literal dollars and backslashes. */
    fun copyVerbatim(from: Int, to: Int) {
        out.append(source, from, to)
        i = to
    }

    while (i < source.length) {
        if (lineStart) {
            var start = i
            while (start < source.length && start - i < 3 && source[start] == ' ') start++
            val candidate = source.getOrNull(start)
            if (candidate == '`' || candidate == '~') {
                var end = start
                while (end < source.length && source[end] == candidate) end++
                val length = end - start
                if (length >= 3) {
                    val lineEnd =
                        source.indexOf('\n', end).let { if (it == -1) source.length else it }
                    var blankTail = true
                    var containsBacktick = false
                    for (position in end until lineEnd) {
                        if (!source[position].isWhitespace()) blankTail = false
                        if (source[position] == '`') containsBacktick = true
                    }
                    if (fenceLength == 0 && (candidate == '~' || !containsBacktick)) {
                        fenceChar = candidate
                        fenceLength = length
                    } else if (candidate == fenceChar && length >= fenceLength && blankTail) {
                        fenceLength = 0
                    }
                    if (fenceLength != 0 || candidate == fenceChar) {
                        copyVerbatim(i, if (lineEnd < source.length) lineEnd + 1 else lineEnd)
                        lineStart = true
                        continue
                    }
                }
            }
        }
        if (fenceLength != 0) {
            val nl = source.indexOf('\n', i)
            val end = if (nl == -1) source.length else nl + 1
            copyVerbatim(i, end)
            lineStart = end > i && source[end - 1] == '\n'
            continue
        }
        val c = source[i]
        val escaped = i > 0 && source[i - 1] == '\\' && !(i > 1 && source[i - 2] == '\\')

        // CommonMark code spans close with a backtick run of exactly the opening length.
        if (c == '`') {
            var end = i + 1
            while (end < source.length && source[end] == '`') end++
            val ticks = end - i
            var close = source.indexOf('`', end)
            while (close >= 0) {
                var closeEnd = close + 1
                while (closeEnd < source.length && source[closeEnd] == '`') closeEnd++
                if (closeEnd - close == ticks) break
                close = source.indexOf('`', closeEnd)
            }
            copyVerbatim(i, if (close >= 0) close + ticks else end)
            lineStart = source.getOrNull(i - 1) == '\n'
            continue
        }

        when {
            !escaped && c == '$' && source.startsWith("$$", i) -> {
                val end = source.indexOf("$$", i + 2)
                val body = if (end > i + 2) source.substring(i + 2, end) else null
                if (body != null) {
                    out.append(mark(MathSpan(body.trim(), block = true)))
                    i = end + 2
                } else {
                    out.append(c)
                    i++
                }
            }

            !escaped && c == '$' -> {
                val end = closingDollar(source, i + 1)
                if (end > 0) {
                    out.append(mark(MathSpan(source.substring(i + 1, end).trim(), block = false)))
                    i = end + 1
                } else {
                    out.append(c)
                    i++
                }
            }

            !escaped && source.startsWith("\\[", i) -> {
                val end = source.indexOf("\\]", i + 2)
                val body = if (end > i + 2) source.substring(i + 2, end) else null
                if (body != null) {
                    out.append(mark(MathSpan(body.trim(), block = true)))
                    i = end + 2
                } else {
                    out.append(c)
                    i++
                }
            }

            !escaped && source.startsWith("\\(", i) -> {
                val end = source.indexOf("\\)", i + 2)
                val body = if (end > i + 2) source.substring(i + 2, end) else null
                if (body != null) {
                    out.append(mark(MathSpan(body.trim(), block = false)))
                    i = end + 2
                } else {
                    out.append(c)
                    i++
                }
            }

            c == '\\' && i + 1 < source.length -> {
                out.append(c).append(source[i + 1])
                i += 2
            }

            else -> {
                out.append(c)
                i++
            }
        }
        lineStart = out.isNotEmpty() && out.last() == '\n'
    }
    return out.toString() to spans
}

/**
 * Finds the `$` closing an inline formula. Returns -1 for amounts like `$5 and $10` (a body with
 * leading/trailing space) and for spans crossing a blank line.
 */
private fun closingDollar(source: String, from: Int): Int {
    var i = from
    while (i < source.length) {
        val c = source[i]
        when {
            c == '\\' -> i += 2
            c == '$' -> {
                val body = source.substring(from, i)
                return if (body.isNotBlank() && !body.startsWith(" ") && !body.endsWith(" ")) i else -1
            }

            c == '\n' && i + 1 < source.length && source[i + 1] == '\n' -> return -1
            else -> i++
        }
    }
    return -1
}

internal fun stripMathDelimiters(text: String): String = when {
    text.startsWith("$$") && text.endsWith("$$") && text.length > 4 -> text.substring(
        2,
        text.length - 2
    ).trim()

    text.startsWith("\\[") && text.endsWith("\\]") -> text.substring(2, text.length - 2).trim()
    else -> text
}
