package dev.undefinedteam.wearagent.presentation.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color

/**
 * TeX math style a formula is rendered in. Inline formulas sit in running text and must keep
 * operators compact (limits to the side); display formulas stand alone and use full-size layout
 * (limits above and below). Rendering both in display style — which the stock drawable does —
 * inflates inline formulas to block height.
 */
internal enum class MathStyle(val texStyle: Int) {
    INLINE(TeXConstants.STYLE_TEXT),
    DISPLAY(TeXConstants.STYLE_DISPLAY),
}

private data class FormulaKey(val source: String, val size: Float, val style: MathStyle)

private val formulaCache = HashMap<FormulaKey, Bitmap>()

/** A previously successful render, or null when this formula has not rendered yet or cannot. */
internal fun cachedFormula(source: String, size: Float, style: MathStyle): Bitmap? =
    synchronized(formulaCache) { formulaCache[FormulaKey(source, size, style)] }

/**
 * Rasterizes LaTeX to a white-on-transparent bitmap, memoized by source, size and style.
 *
 * jlatexmath keeps its macro table in process-wide statics, so `\newcommand`/`\renewcommand` in one
 * formula would otherwise redefine commands for every formula rendered afterwards — across messages
 * and across sessions. The table is snapshotted and restored around each parse, keeping formulas
 * isolated while still allowing a macro to be defined and used within a single formula.
 *
 * Only successful renders are cached. A formula that failed while jlatexmath was still loading its
 * fonts would otherwise stay blank for the lifetime of the process, since a cached null is never
 * retried; leaving failures uncached lets the next attempt succeed.
 */
internal fun renderFormula(source: String, size: Float, style: MathStyle): Bitmap? =
    synchronized(formulaCache) {
        val key = FormulaKey(source, size, style)
        formulaCache[key]?.let { return it }
        ensureCommandTableInitialized()
        val commands = HashMap(MacroInfo.Commands)
        val bitmap = try {
            rasterize(source, size, style)
        } finally {
            MacroInfo.Commands.clear()
            MacroInfo.Commands.putAll(commands)
        }
        if (bitmap != null) formulaCache[key] = bitmap
        bitmap
    }

/**
 * jlatexmath registers its built-in command aliases (`\dfrac`, `\operatorname`, the matrix
 * environments, ...) in static initializers that only run when their owning classes are first
 * touched. Reading [MacroInfo.Commands] before that yields an incomplete table, so a later restore
 * would drop those built-ins permanently. Touching the classes first guarantees the snapshot is
 * taken against the full table.
 */
private fun ensureCommandTableInitialized() {
    try {
        Class.forName("org.scilab.forge.jlatexmath.PredefinedCommands")
        Class.forName("org.scilab.forge.jlatexmath.PredefMacros")
    } catch (_: Throwable) {
    }
}

private fun rasterize(source: String, size: Float, style: MathStyle): Bitmap? = try {
    val icon = TeXFormula(source).TeXIconBuilder()
        .setStyle(style.texStyle)
        .setSize(size)
        .setFGColor(Color(0xFFFFFFFF.toInt()))
        .build()
    val width = icon.iconWidth.coerceAtLeast(1)
    val height = icon.iconHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val graphics = AndroidGraphics2D()
    graphics.setCanvas(Canvas(bitmap))
    icon.paintIcon(null, graphics, 0, 0)
    bitmap
} catch (_: Throwable) {
    null
}
