package com.lj.blackcaptions

import android.content.Context
import androidx.core.content.edit

enum class TextSize(val label: String, val sp: Float) { S("S", 14f), M("M", 18f), L("L", 24f), XL("XL", 32f) }

/** How dark the overlay's background is. */
enum class Shade(val label: String, val alpha: Int) {
    LIGHT("40%", 102), MEDIUM("70%", 179), DARK("90%", 230), SOLID("100%", 255)
}

/** Persisted settings. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("captions", Context.MODE_PRIVATE)

    /** The chosen Vosk model's folder name, and its language code from the model list (e.g. "pl", "en-us"). */
    fun model(c: Context) = sp(c).getString("model", null)
    fun lang(c: Context) = sp(c).getString("lang", null)
    fun language(c: Context) = sp(c).getString("language", null)
    fun setModel(c: Context, m: VoskModel) = sp(c).edit {
        putString("model", m.name)
        putString("lang", m.lang)
        putString("language", m.language)
    }

    /** Re-read finished phrases with the accurate model, when one is downloaded for the language. */
    fun accurate(c: Context) = sp(c).getBoolean("accurate", true)
    fun setAccurate(c: Context, on: Boolean) = sp(c).edit { putBoolean("accurate", on) }

    /** ML Kit language code to translate into, or null for no translation. */
    fun target(c: Context) = sp(c).getString("target", null)
    fun setTarget(c: Context, code: String?) = sp(c).edit { putString("target", code) }

    fun textSize(c: Context) = TextSize.entries.firstOrNull { it.name == sp(c).getString("size", null) } ?: TextSize.M
    fun setTextSize(c: Context, s: TextSize) = sp(c).edit { putString("size", s.name) }

    fun shade(c: Context) = Shade.entries.firstOrNull { it.name == sp(c).getString("shade", null) } ?: Shade.MEDIUM
    fun setShade(c: Context, s: Shade) = sp(c).edit { putString("shade", s.name) }

    /** Overlay position and size in pixels; -1 until the user moves it. */
    fun overlay(c: Context) = sp(c).let { IntArray(4) { i -> it.getInt("overlay$i", -1) } }
    fun setOverlay(c: Context, x: Int, y: Int, w: Int, h: Int) = sp(c).edit {
        intArrayOf(x, y, w, h).forEachIndexed { i, v -> putInt("overlay$i", v) }
    }
}
