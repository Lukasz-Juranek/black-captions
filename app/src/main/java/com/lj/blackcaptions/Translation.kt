package com.lj.blackcaptions

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlKit
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/** On-device translation through ML Kit. Each language model (~30 MB) downloads on first use. */
object Translation {
    /** All target languages, sorted by English name. */
    val languages: List<String> by lazy { TranslateLanguage.getAllLanguages().sortedBy(::name) }

    fun name(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }

    /** The ML Kit code for a Vosk language code, or null when ML Kit can't translate from it. */
    fun fromVosk(lang: String): String? {
        val code = when (val base = lang.substringBefore('-')) {
            "cn" -> "zh"
            "ua" -> "uk"
            "vn" -> "vi"
            "kz" -> "kk"
            else -> base
        }
        return TranslateLanguage.fromLanguageTag(code)
    }

    fun client(source: String, target: String): Translator =
        MlKit.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
}
