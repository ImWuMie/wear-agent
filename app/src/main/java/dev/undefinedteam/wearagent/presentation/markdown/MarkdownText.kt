package dev.undefinedteam.wearagent.presentation.markdown

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A rendered unit: either text (with optional inline formulas), a code block, display math, or a table. */
internal sealed interface Block {
    /** [inline] maps inline-content ids to their LaTeX source; see [inlineContentFor]. */
    class Rich(val text: AnnotatedString, val inline: Map<String, String>) : Block
    class CodeBlock(val text: String) : Block
    class Formula(val source: String) : Block
    class Table(val content: Rich) : Block
}

/** Renders CommonMark with GFM tables/strikethrough and LaTeX math, sized for the watch display. */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    mathFitWidth: Boolean = true,
    color: Color = Color.Unspecified,
) {
    val blocks = remember(source) { parse(source) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is Block.Rich -> RichTextBlock(block, MaterialTheme.typography.bodyMedium, color)

                is Block.CodeBlock -> CodeBlockSurface(block.text)
                is Block.Formula -> FormulaImage(block.source, mathFitWidth)
                is Block.Table -> RichTextBlock(block.content, MaterialTheme.typography.bodySmall, color)
            }
        }
    }
}

/**
 * Wear's fixed line height overrides tall inline placeholders. Use natural per-line metrics for
 * math-bearing text: fractions get their full height, while ordinary lines stay compact.
 */
@Composable
private fun RichTextBlock(block: Block.Rich, style: TextStyle, color: Color = Color.Unspecified) {
    val inline = inlineContentFor(block.inline)
    val mathStyle = if (inline.isEmpty()) style else {
        style.copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)
    }
    Text(block.text, inlineContent = inline, style = mathStyle, color = color)
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
            val bitmap = renderFormula(latex, sizePx, MathStyle.INLINE)
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
private fun FormulaImage(source: String, fitWidth: Boolean) {
    val density = LocalDensity.current
    val size = with(density) { 15.sp.toPx() }
    // produceState otherwise retains the previous block's bitmap when streaming changes its source.
    key(source, size) {
        val image by produceState<Bitmap?>(initialValue = cachedFormula(source, size, MathStyle.DISPLAY)) {
            value = withContext(Dispatchers.Default) {
                renderFormula(source, size, MathStyle.DISPLAY)
            }
        }
        val bitmap = image
        if (bitmap == null) {
            Text(
                source,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (fitWidth) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = source,
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            )
        } else {
            // Scrollable mode: the formula keeps its natural size and can be panned sideways when
            // it is wider than the screen, instead of being scaled down to fit.
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = source,
                    Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
