package dev.undefinedteam.wearagent.presentation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import java.util.IdentityHashMap

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

private sealed interface Block {
    data class Rich(val text: AnnotatedString) : Block
    data class CodeBlock(val text: String) : Block
    data class Formula(val source: String) : Block
    data class Table(val rows: List<List<String>>) : Block
}

@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parse(source) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is Block.Rich -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                is Block.CodeBlock -> CodeBlockSurface(block.text)
                is Block.Formula -> FormulaImage(block.source)
                is Block.Table -> Text(
                    block.rows.joinToString("\n") { it.joinToString(" | ") },
                    style = MaterialTheme.typography.bodySmall
                )
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
    val size = with(LocalDensity.current) { 15.sp.toPx() }
    // Render off the main thread; cache hits are synchronous.
    val image by produceState<Bitmap?>(initialValue = formulaCache[source], source, size) {
        value = if (value != null) value
        else withContext(Dispatchers.Default) { renderFormula(source, size) }
    }
    val bitmap = image
    if (bitmap == null) {
        Text(
            source,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = source,
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )
    }
}

private fun parse(source: String): List<Block> {
    val blocks = ArrayList<Block>()
    // Lambda replacement: a literal "$$" replacement string would be parsed as group references.
    val prepared = source.replace(Regex("""(?m)^\$\$\s*$""")) { "$$" }
    parser.parse(prepared).accept(object : AbstractVisitor() {
        override fun visit(paragraph: Paragraph) {
            splitFormulas(textOf(paragraph)).forEach(blocks::add)
        }

        override fun visit(heading: Heading) {
            val title = textOf(heading)
            splitFormulas(title).forEach { block ->
                when (block) {
                    is Block.Rich -> blocks.add(
                        Block.Rich(
                            buildAnnotatedString {
                                append(block.text)
                                addStyle(
                                    SpanStyle(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = (18 - heading.level).sp
                                    ), 0, length
                                )
                            },
                        ),
                    )

                    else -> blocks.add(block)
                }
            }
        }

        override fun visit(block: FencedCodeBlock) {
            val info = block.info?.trim()?.lowercase()
            val text = block.literal.trim()
            val looksMath = info in setOf("math", "latex", "tex", "formula") ||
                    text.startsWith("\\begin{") ||
                    (text.startsWith("\\[") && text.endsWith("\\]")) ||
                    text.startsWith("\$")
            if (looksMath) blocks.add(Block.Formula(text)) else blocks.add(Block.CodeBlock(block.literal.trimEnd()))
        }

        override fun visit(block: IndentedCodeBlock) {
            blocks.add(Block.CodeBlock(block.literal.trimEnd()))
        }

        override fun visit(quote: BlockQuote) {
            addMixed(textOf(quote))
        }

        override fun visit(list: BulletList) {
            addList(list, null)
        }

        override fun visit(list: OrderedList) {
            addList(list, list.markerStartNumber)
        }

        override fun visit(custom: org.commonmark.node.CustomBlock) {
            val table = custom as? TableBlock ?: return
            val rows = ArrayList<List<String>>()
            var row = table.firstChild
            while (row != null) {
                val cells = ArrayList<String>()
                var cell = row.firstChild
                while (cell != null) {
                    cells.add(plain(cell))
                    cell = cell.next
                }
                if (cells.isNotEmpty()) rows.add(cells)
                row = row.next
            }
            blocks.add(Block.Table(rows))
        }

        private fun addMixed(text: AnnotatedString) {
            splitFormulas(text).forEach(blocks::add)
        }

        private fun addList(list: Node, startNumber: Int?) {
            var child = list.firstChild
            var number = startNumber ?: 1
            while (child != null) {
                val marker = if (startNumber == null) "• " else "$number. "
                addMixed(buildAnnotatedString {
                    append(marker)
                    append(textOf(child))
                })
                child = child.next
                number++
            }
        }
    })
    return blocks.ifEmpty { listOf(Block.Rich(AnnotatedString(source))) }
}

private fun splitFormulas(text: AnnotatedString): List<Block> {
    val blocks = ArrayList<Block>()
    // Block $$..$$ first; inline $..$ may span lines but not empty lines.
    val pattern = Regex("""\$\$([\s\S]+?)\$\$|\$((?:[^$\n]|\n(?!\n))+)\$""")

    var cursor = 0
    pattern.findAll(text.text).forEach { match ->
        if (match.range.first > cursor) blocks.add(
            Block.Rich(
                text.subSequence(
                    cursor,
                    match.range.first
                )
            )
        )
        blocks.add(Block.Formula((match.groupValues[1].ifBlank { match.groupValues[2] }).trim()))
        cursor = match.range.last + 1
    }
    if (cursor < text.length) blocks.add(Block.Rich(text.subSequence(cursor, text.length)))
    return blocks.ifEmpty { listOf(Block.Rich(text)) }
}

private fun textOf(node: Node): AnnotatedString =
    buildAnnotatedString { appendNode(node.firstChild, SpanStyle()) }

private fun AnnotatedString.Builder.appendNode(start: Node?, style: SpanStyle) {
    var node = start
    while (node != null) {
        when (node) {
            is org.commonmark.node.Text -> styled(node.literal, style)
            is Code -> styled(
                node.literal,
                style.merge(SpanStyle(fontFamily = FontFamily.Monospace))
            )

            is Emphasis -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(fontStyle = FontStyle.Italic))
            )

            is StrongEmphasis -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(fontWeight = FontWeight.Bold))
            )

            is Strikethrough -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
            )

            is Link -> appendNode(
                node.firstChild,
                style.merge(SpanStyle(textDecoration = TextDecoration.Underline))
            )

            is SoftLineBreak, is org.commonmark.node.HardLineBreak -> {
                // commonmark folds a trailing \\ into the Text literal as a single \;
                // restore the second \ so LaTeX line breaks survive.
                val prev = node.previous
                if (prev is org.commonmark.node.Text && prev.literal.endsWith("\\")) append('\\')
                append('\n')
            }

            is org.commonmark.node.HtmlInline -> styled(node.literal, style)
            else -> appendNode(node.firstChild, style)
        }
        node = node.next
    }
}

private fun AnnotatedString.Builder.styled(value: String, style: SpanStyle) {
    val start = length
    append(value)
    addStyle(style, start, length)
}

private fun listText(list: Node, startNumber: Int?): AnnotatedString = buildAnnotatedString {
    var child = list.firstChild
    var number = startNumber ?: 1
    while (child != null) {
        if (length > 0) append('\n')
        append(if (startNumber == null) "• " else "$number. ")
        append(plain(child))
        child = child.next
        number++
    }
}

private fun plain(node: Node): String {
    val out = StringBuilder()
    node.accept(object : AbstractVisitor() {
        override fun visit(text: org.commonmark.node.Text) {
            out.append(text.literal)
        }

        override fun visit(code: Code) {
            out.append(code.literal)
        }

        override fun visit(soft: SoftLineBreak) {
            out.append('\n')
        }

        override fun visit(hard: org.commonmark.node.HardLineBreak) {
            out.append('\n')
        }

        override fun visit(custom: org.commonmark.node.CustomNode) {
            if (custom !is TableCell) visitChildren(custom)
        }
    })
    return out.toString().trim()
}

private val formulaCache = IdentityHashMap<String, Bitmap?>()

/** Rewrites LaTeX this jlatexmath fork cannot parse (amsmath envs, \LaTeX) into equivalents. */
private fun normalizeFormula(source: String): String {
    var result = source.replace("\\\\LaTeX", "\\\\mathrm{\\\\TeX}")

    data class Fence(val open: String, val close: String)

    val fences = mapOf(
        "bmatrix" to Fence("[", "]"),
        "Bmatrix" to Fence("{", "}"),
        "pmatrix" to Fence("(", ")"),
        "vmatrix" to Fence("|", "|"),
        "Vmatrix" to Fence("\\\\Vert", "\\\\Vert"),
    )
    fences.forEach { (env, fence) ->
        result = result
            .replace("\\\\begin{$env}", "\\\\left${fence.open}\\\\begin{array}{ccc}")
            .replace("\\\\end{$env}", "\\\\end{array}\\\\right${fence.close}")
    }
    return result
}

private fun renderFormula(source: String, size: Float): Bitmap? = synchronized(formulaCache) {
    if (formulaCache.containsKey(source)) return formulaCache[source]
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
    formulaCache[source] = bitmap
    bitmap
}
