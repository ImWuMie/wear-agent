package dev.undefinedteam.wearagent.presentation.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color
import androidx.core.graphics.createBitmap

/** Inline formulas keep operators compact; display formulas use full-size layout. */
internal enum class MathStyle(val texStyle: Int) {
    INLINE(TeXConstants.STYLE_TEXT),
    DISPLAY(TeXConstants.STYLE_DISPLAY),
}

private data class FormulaKey(val source: String, val size: Float, val style: MathStyle)

private val formulaCache = HashMap<FormulaKey, Bitmap>()

internal fun cachedFormula(source: String, size: Float, style: MathStyle): Bitmap? =
    synchronized(formulaCache) { formulaCache[FormulaKey(source, size, style)] }

/**
 * Rasterizes LaTeX to a white-on-transparent bitmap, memoized by source, size and style. Failures
 * are not cached, so a render that lost a race with font loading can succeed on the next attempt.
 */
internal fun renderFormula(source: String, size: Float, style: MathStyle): Bitmap? =
    synchronized(formulaCache) {
        val key = FormulaKey(source, size, style)
        formulaCache[key]?.let { return it }
        // jlatexmath's macro table is process-wide, so a \newcommand in one formula would redefine
        // commands for every formula rendered afterwards. Snapshot and restore around each parse.
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
 * Built-in aliases (`\dfrac`, `\operatorname`, matrix environments) register lazily on first parse,
 * so the snapshot above is only complete once their owning classes have loaded.
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
    val bitmap = createBitmap(width, height)
    val graphics = AndroidGraphics2D()
    graphics.setCanvas(Canvas(bitmap))
    icon.paintIcon(null, graphics, 0, 0)
    bitmap
} catch (_: Throwable) {
    null
}
