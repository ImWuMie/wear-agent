package dev.undefinedteam.wearagent.presentation.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.undefinedteam.wearagent.presentation.markdown.MarkdownText
import dev.undefinedteam.wearagent.presentation.theme.WearAgentTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.noties.jlatexmath.JLatexMathDrawable

class MarkdownTextTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedFormulaIsReplacedWhenStreamingChangesTheSameBlock() {
        val replacement = "\\frac{1}{2}"
        var source by mutableStateOf("\\[x+1\\]")
        compose.setContent {
            WearAgentTheme {
                Column {
                    Box(Modifier.size(200.dp, 80.dp).background(Color.Black).testTag("changing")) {
                        MarkdownText(source)
                    }
                    Box(Modifier.size(200.dp, 80.dp).background(Color.Black).testTag("reference")) {
                        MarkdownText("\\[$replacement\\]")
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("x+1", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription(replacement, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { source = "\\[$replacement\\]" }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(replacement, useUnmergedTree = true)
                .fetchSemanticsNodes().size == 2
        }
        assertArrayEquals(pixels("reference"), pixels("changing"))
    }

    @Test
    fun inlineFormulaUsesTheCurrentFontScaleInsteadOfACachedSmallerBitmap() {
        val formula = "a+b"
        var sizePx = 0f
        compose.setContent {
            val density = LocalDensity.current
            WearAgentTheme {
                Column {
                    MarkdownText("\\($formula\\)")
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                        sizePx = with(LocalDensity.current) { 15.sp.toPx() }
                        MarkdownText("\\($formula\\)")
                    }
                }
            }
        }
        val expectedWidth = compose.runOnIdle {
            JLatexMathDrawable.builder(formula).textSize(sizePx).build().intrinsicWidth.toFloat()
        }
        val images = compose.onAllNodesWithContentDescription(formula, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(2, images.size)
        assertEquals(expectedWidth, images[1].boundsInRoot.width, 1f)
    }

    @Test
    fun tableCellsKeepTheirTextAndRenderInlineMath() {
        compose.setContent {
            WearAgentTheme {
                MarkdownText("| Name | Formula |\n| --- | --- |\n| Square | ${'$'}x^2${'$'} |")
            }
        }
        compose.onNodeWithText("Square", substring = true).assertExists()
        compose.onNodeWithContentDescription("x^2", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tildeCodeFenceKeepsLiteralMathDelimiters() {
        compose.setContent {
            WearAgentTheme {
                MarkdownText("~~~text\n${'$'}${'$'}x^2${'$'}${'$'}\n~~~")
            }
        }
        compose.onNodeWithText("${'$'}${'$'}x^2${'$'}${'$'}").assertExists()
        compose.onAllNodes(hasContentDescription("x^2"), useUnmergedTree = true)
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test
    fun trigonometricFractionFromTheReportedConversationRendersAsMath() {
        val formula = "\\sin a-\\cos a=\\dfrac{1}{5}"
        compose.setContent {
            WearAgentTheme { MarkdownText("\\($formula\\)") }
        }
        compose.onNodeWithContentDescription(formula, useUnmergedTree = true).assertExists()
    }

    @Test
    fun tallInlineFormulasDoNotOverlapWithinOrBetweenParagraphs() {
        val upper = "\\dfrac{1}{\\dfrac{2}{3}}"
        val lower = "\\dfrac{4}{\\dfrac{5}{6}}"
        var separator by mutableStateOf("\n")
        compose.setContent {
            WearAgentTheme {
                Box(Modifier.size(200.dp, 180.dp)) {
                    MarkdownText("\\($upper\\)$separator\\($lower\\)")
                }
            }
        }
        for (paragraphSeparator in listOf("\n", "\n\n")) {
            compose.runOnIdle { separator = paragraphSeparator }
            val first = compose.onNodeWithContentDescription(upper, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithContentDescription(lower, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(
                "Formula bounds overlap for ${paragraphSeparator.length} newlines: $first and $second",
                second.top >= first.bottom,
            )
        }
    }

    @Test
    fun proseBeforeTallMathKeepsFullFormulaHeightAtBothFontScales() {
        val upper = "\\dfrac{1}{\\dfrac{2}{3}}"
        val lower = "\\dfrac{4}{5}"
        var fontScale by mutableStateOf(1f)
        var densityPx = 0f
        var sizePx = 0f
        compose.setContent {
            val density = LocalDensity.current
            densityPx = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                sizePx = with(LocalDensity.current) { 15.sp.toPx() }
                WearAgentTheme {
                    Box(Modifier.size(200.dp, 220.dp).verticalScroll(rememberScrollState())) {
                        MarkdownText("Intro\n\\($upper\\)\n\\($lower\\)")
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            val first = compose.onNodeWithContentDescription(upper, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val second = compose.onNodeWithContentDescription(lower, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("Formulas overlap at fontScale $scale", second.top >= first.bottom)
            val expectedHeight = compose.runOnIdle {
                JLatexMathDrawable.builder(upper).textSize(sizePx).build().intrinsicHeight.toFloat()
            }
            assertEquals(expectedHeight, (first.bottom - first.top).value * densityPx, 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Intro", substring = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val ordinaryLineHeight = layouts.single().let { it.getLineBottom(0) - it.getLineTop(0) }
            org.junit.Assert.assertTrue(
                "Ordinary text was stretched to tall formula height at fontScale $scale",
                ordinaryLineHeight < expectedHeight / 2,
            )
        }
    }

    @Test
    fun displayFormulaSeparatorsDoNotAddBlankTextLines() {
        compose.setContent {
            WearAgentTheme {
                Column(Modifier.size(200.dp, 220.dp)) {
                    MarkdownText("Before\n\\[\\dfrac{1}{2}\\]\nAfter")
                    MarkdownText("Reference", Modifier.testTag("reference"))
                }
            }
        }
        val reference = compose.onNodeWithTag("reference").getUnclippedBoundsInRoot()
        for (text in listOf("Before", "After")) {
            val bounds = compose.onNodeWithText(text, substring = true).getUnclippedBoundsInRoot()
            assertEquals(
                "Display math left an extra blank text line beside $text",
                (reference.bottom - reference.top).value,
                (bounds.bottom - bounds.top).value,
                0.5f,
            )
        }
    }

    private fun pixels(tag: String): IntArray {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }
}
