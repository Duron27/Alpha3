package org.alpha3.launcher.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Optional on-device translation of UI text. Shared screens read it from [LocalTextTranslator];
 * a platform that has no translator simply leaves the default (null) in place.
 */
interface TextTranslator {
    /** Whether the user switched translation on. */
    @Composable
    fun isEnabled(): Boolean

    /** Translates [text] and reports the result (possibly several times, e.g. "Translating...") via [onResult]. */
    @Composable
    fun Translate(text: String, onResult: (String) -> Unit)
}

val LocalTextTranslator = staticCompositionLocalOf<TextTranslator?> { null }

/** Translates [inputText] when a translator is installed; otherwise the text is left alone. */
@Composable
fun TranslateText(inputText: String, onTranslationResult: (String) -> Unit) {
    LocalTextTranslator.current?.Translate(inputText, onTranslationResult)
}
