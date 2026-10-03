package dev.undefinedteam.wearagent.presentation

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Applies the persisted language override ("" = follow system) to a base context. */
object LocaleHelper {
    fun wrap(context: Context, language: String): Context {
        if (language.isBlank()) return context
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
