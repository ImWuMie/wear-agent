package dev.undefinedteam.wearagent.presentation.markdown

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
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

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

/** Parses the source into render blocks, extracting math first so markdown cannot escape it. */
internal fun parse(source: String): List<Block> {
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
                addMixed(
                    Block.Rich(
                        buildAnnotatedString { append(marker); append(item.text) },
                        item.inline
                    )
                )
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
        val span =
            spans.getOrNull(match.groupValues[1].toIntOrNull() ?: return@forEach) ?: return@forEach
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
            is Code -> styled(
                node.literal,
                style.merge(SpanStyle(fontFamily = FontFamily.Monospace))
            )

            is Emphasis -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(fontStyle = FontStyle.Italic)),
                spans,
                inline
            )

            is StrongEmphasis -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(fontWeight = FontWeight.Bold)),
                spans,
                inline
            )

            is Strikethrough -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)),
                spans,
                inline
            )

            is Link -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(textDecoration = TextDecoration.Underline)),
                spans,
                inline
            )

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
