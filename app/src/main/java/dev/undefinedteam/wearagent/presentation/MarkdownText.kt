package dev.undefinedteam.wearagent.presentation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.parser.Parser
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.HashMap

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

/** Math is cut out of the source before markdown runs, so LaTeX `\\`, `\{`, `\_` survive verbatim. */
private class MathSpan(val source: String, val block: Boolean)

private const val MARK_OPEN = '\uE000'
private const val MARK_CLOSE = '\uE001'

/** Placeholder left in the markdown text where a [MathSpan] used to be. */
private val markerPattern = Regex("\uE000(\\d+)\uE001")

private sealed interface Block {
    /** [inline] maps inline-content ids to their LaTeX source; see [inlineContentFor]. */
    class Rich(val text: AnnotatedString, val inline: Map<String, String>) : Block
    class CodeBlock(val text: String) : Block
    class Formula(val source: String) : Block
    class Table(val content: Rich) : Block
}

@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parse(source) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is Block.Rich -> RichTextBlock(block, MaterialTheme.typography.bodyMedium)

                is Block.CodeBlock -> CodeBlockSurface(block.text)
                is Block.Formula -> FormulaImage(block.source)
                is Block.Table -> RichTextBlock(block.content, MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Wear's fixed line height overrides tall inline placeholders. Use natural per-line metrics for
 * math-bearing text: fractions get their full height, while ordinary lines stay compact.
 */
@Composable
private fun RichTextBlock(block: Block.Rich, style: TextStyle) {
    val inline = inlineContentFor(block.inline)
    val mathStyle = if (inline.isEmpty()) style else {
        style.copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)
    }
    Text(block.text, inlineContent = inline, style = mathStyle)
}

/**
 * Builds the inline-content map with bitmap-sized placeholders centered within each line.
 */
@Composable
private fun inlineContentFor(sources: Map<String, String>): Map<String, InlineTextContent> {
    if (sources.isEmpty()) return emptyMap()
    val density = LocalDensity.current
    val sizePx = with(density) { 15.sp.toPx() }
    return remember(sources, sizePx, density) {
        sources.mapValues { (_, latex) ->
            val bitmap = renderFormula(latex, sizePx)
            if (bitmap != null) {
                InlineTextContent(
                    Placeholder(
                        width = with(density) { bitmap.width.toSp() },
                        height = with(density) { bitmap.height.toSp() },
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                    ),
                ) {
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = latex,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                // Unparsable formula: keep it readable inline rather than dropping it.
                InlineTextContent(
                    Placeholder(
                        width = (latex.length.coerceIn(3, 24) * 7).sp,
                        height = 15.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                    ),
                ) {
                    Text(latex, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CodeBlockSurface(code: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            code,
            Modifier.fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FormulaImage(source: String) {
    val density = LocalDensity.current
    val size = with(density) { 15.sp.toPx() }
    // produceState otherwise retains the previous block's bitmap when streaming changes its source.
    key(source, size) {
        val image by produceState<Bitmap?>(initialValue = cachedFormula(source, size)) {
            value = withContext(Dispatchers.Default) { renderFormula(source, size) }
        }
        val bitmap = image
        if (bitmap == null) {
            Text(
                source,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = source,
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            )
        }
    }
}

// region source extraction

/**
 * Replaces every math span with an opaque marker before markdown parsing, so markdown escaping
 * cannot touch LaTeX internals. Handles `$$..$$`, `$..$`, `\[..\]`, `\(..\)`.
 *
 * Fences and inline code are copied verbatim: a literal `$` or `\(` inside code is not a formula,
 * and pairing its opening delimiter with a later real one would swallow live text.
 */
private fun extractMath(source: String): Pair<String, List<MathSpan>> {
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
                    val lineEnd = source.indexOf('\n', end).let { if (it == -1) source.length else it }
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

private fun stripMathDelimiters(text: String): String = when {
    text.startsWith("$$") && text.endsWith("$$") && text.length > 4 -> text.substring(2, text.length - 2).trim()
    text.startsWith("\\[") && text.endsWith("\\]") -> text.substring(2, text.length - 2).trim()
    else -> text
}

// endregion

private fun parse(source: String): List<Block> {
    val (marked, spans) = extractMath(source)
    val blocks = ArrayList<Block>()
    parser.parse(marked).accept(object : AbstractVisitor() {
        override fun visit(paragraph: Paragraph) {
            addMixed(textOf(paragraph, spans))
        }

        override fun visit(heading: Heading) {
            val rich = textOf(heading, spans)
            val text = buildAnnotatedString {
                append(rich.text)
                addStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = (18 - heading.level).sp),
                    0,
                    length,
                )
            }
            addMixed(Block.Rich(text, rich.inline))
        }

        override fun visit(block: FencedCodeBlock) {
            val info = block.info?.trim()?.lowercase()
            val text = block.literal.trim()
            // An explicit non-math language denotes literal code, even when it contains delimiters.
            val looksMath = info in setOf("math", "latex", "tex", "formula") ||
                    (info.isNullOrBlank() && (
                        text.startsWith("\\begin{") ||
                        text.startsWith("\\[") ||
                        text.startsWith("$$")
                    ))
            if (looksMath) {
                blocks.add(Block.Formula(stripMathDelimiters(text)))
            } else {
                blocks.add(Block.CodeBlock(block.literal.trimEnd()))
            }
        }

        override fun visit(block: IndentedCodeBlock) {
            blocks.add(Block.CodeBlock(block.literal.trimEnd()))
        }

        override fun visit(quote: BlockQuote) {
            addMixed(textOf(quote, spans))
        }

        override fun visit(list: BulletList) {
            addList(list, null)
        }

        override fun visit(list: OrderedList) {
            addList(list, list.markerStartNumber)
        }

        override fun visit(custom: org.commonmark.node.CustomBlock) {
            val table = custom as? TableBlock ?: return
            val inline = LinkedHashMap<String, String>()
            val text = buildAnnotatedString {
                var section = table.firstChild
                var firstRow = true
                while (section != null) {
                    var row = section.firstChild
                    while (row != null) {
                        if (!firstRow) append('\n')
                        firstRow = false
                        var cell = row.firstChild
                        var firstCell = true
                        while (cell != null) {
                            if (!firstCell) append(" | ")
                            firstCell = false
                            val rich = textOf(cell, spans)
                            append(rich.text)
                            inline.putAll(rich.inline)
                            cell = cell.next
                        }
                        row = row.next
                    }
                    section = section.next
                }
            }
            blocks.add(Block.Table(Block.Rich(text, inline)))
        }

        private fun addMixed(rich: Block.Rich) {
            splitBlocks(rich, spans).forEach(blocks::add)
        }

        private fun addList(list: Node, startNumber: Int?) {
            var child = list.firstChild
            var number = startNumber ?: 1
            while (child != null) {
                val marker = if (startNumber == null) "\u2022 " else "$number. "
                val item = textOf(child, spans)
                addMixed(Block.Rich(buildAnnotatedString { append(marker); append(item.text) }, item.inline))
                child = child.next
                number++
            }
        }
    })
    return blocks.ifEmpty { listOf(Block.Rich(AnnotatedString(source), emptyMap())) }
}

/**
 * Splits at block math markers, keeping only each slice's referenced inline formulas.
 * Newlines adjoining display math separate blocks; retaining them would add empty text lines.
 */
private fun splitBlocks(rich: Block.Rich, spans: List<MathSpan>): List<Block> {
    val out = ArrayList<Block>()
    var cursor = 0
    fun addText(start: Int, end: Int) {
        var from = start
        var to = end
        while (from < to && rich.text[from] == '\n') from++
        while (to > from && rich.text[to - 1] == '\n') to--
        if (from == to) return
        val text = rich.text.subSequence(from, to)
        val inline = buildMap {
            text.getStringAnnotations(0, text.length).forEach { annotation ->
                rich.inline[annotation.item]?.let { put(annotation.item, it) }
            }
        }
        out.add(Block.Rich(text, inline))
    }
    markerPattern.findAll(rich.text.text).forEach { match ->
        val span = spans.getOrNull(match.groupValues[1].toIntOrNull() ?: return@forEach) ?: return@forEach
        if (!span.block) return@forEach
        if (match.range.first > cursor) {
            addText(cursor, match.range.first)
        }
        out.add(Block.Formula(span.source))
        cursor = match.range.last + 1
    }
    if (cursor == 0) return listOf(rich)
    if (cursor < rich.text.length) addText(cursor, rich.text.length)
    return out
}

private fun textOf(node: Node, spans: List<MathSpan>): Block.Rich {
    val inline = LinkedHashMap<String, String>()
    val text = buildAnnotatedString { appendNode(node.firstChild, SpanStyle(), spans, inline) }
    return Block.Rich(text, inline)
}

private fun AnnotatedString.Builder.appendNode(
    start: Node?,
    style: SpanStyle,
    spans: List<MathSpan>,
    inline: MutableMap<String, String>,
) {
    var node = start
    while (node != null) {
        when (node) {
            is org.commonmark.node.Text -> appendMarkers(node.literal, style, spans, inline)
            is Code -> styled(node.literal, style.merge(SpanStyle(fontFamily = FontFamily.Monospace)))
            is Emphasis -> appendNode(node.firstChild, style.merge(SpanStyle(fontStyle = FontStyle.Italic)), spans, inline)
            is StrongEmphasis -> appendNode(node.firstChild, style.merge(SpanStyle(fontWeight = FontWeight.Bold)), spans, inline)
            is Strikethrough -> appendNode(node.firstChild, style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)), spans, inline)
            is Link -> appendNode(node.firstChild, style.merge(SpanStyle(textDecoration = TextDecoration.Underline)), spans, inline)
            is SoftLineBreak, is org.commonmark.node.HardLineBreak -> append('\n')
            is org.commonmark.node.HtmlInline -> styled(node.literal, style)
            else -> appendNode(node.firstChild, style, spans, inline)
        }
        node = node.next
    }
}

/**
 * Emits a text literal, turning inline math markers into inline content placeholders. Block math
 * markers stay in the string; [splitBlocks] cuts the block at them afterwards.
 */
private fun AnnotatedString.Builder.appendMarkers(
    literal: String,
    style: SpanStyle,
    spans: List<MathSpan>,
    inline: MutableMap<String, String>,
) {
    var cursor = 0
    markerPattern.findAll(literal).forEach { match ->
        if (match.range.first > cursor) styled(literal.substring(cursor, match.range.first), style)
        val span = spans.getOrNull(match.groupValues[1].toIntOrNull() ?: -1)
        if (span == null || span.block) {
            styled(match.value, style)
        } else {
            val id = "formula-${match.groupValues[1]}"
            inline[id] = span.source
            appendInlineContent(id, span.source)
        }
        cursor = match.range.last + 1
    }
    if (cursor < literal.length) styled(literal.substring(cursor), style)
}

private fun AnnotatedString.Builder.styled(value: String, style: SpanStyle) {
    val start = length
    append(value)
    addStyle(style, start, length)
}


private data class FormulaKey(val source: String, val size: Float)

private val formulaCache = HashMap<FormulaKey, Bitmap?>()

private fun cachedFormula(source: String, size: Float): Bitmap? = synchronized(formulaCache) {
    formulaCache[FormulaKey(source, size)]
}

/** Rewrites LaTeX this jlatexmath fork cannot parse (amsmath envs, \LaTeX) into equivalents. */
private fun normalizeFormula(source: String): String {
    var result = source.replace("\\LaTeX", "\\mathrm{\\TeX}")

    data class Fence(val open: String, val close: String)

    val fences = mapOf(
        "bmatrix" to Fence("[", "]"),
        "Bmatrix" to Fence("\\{", "\\}"),
        "pmatrix" to Fence("(", ")"),
        "vmatrix" to Fence("|", "|"),
        "Vmatrix" to Fence("\\Vert", "\\Vert"),
    )
    fences.forEach { (env, fence) ->
        result = result
            .replace("\\begin{$env}", "\\left${fence.open}\\begin{array}{ccc}")
            .replace("\\end{$env}", "\\end{array}\\right${fence.close}")
    }
    return result
}

private fun renderFormula(source: String, size: Float): Bitmap? = synchronized(formulaCache) {
    val cacheKey = FormulaKey(source, size)
    if (formulaCache.containsKey(cacheKey)) return formulaCache[cacheKey]
    val bitmap = try {
        val drawable = JLatexMathDrawable.builder(normalizeFormula(source))
            .textSize(size)
            .color(Color.WHITE)
            .build()
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        image
    } catch (_: Throwable) {
        null
    }
    formulaCache[cacheKey] = bitmap
    bitmap
}
