package com.nguyen.flexpad.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Toàn bộ cấu hình người dùng của FlexPad (MỐC 5), lưu bằng DataStore Preferences. */
data class PadSettings(
    val themeId: String = "midnight",
    val customPanelBg: Int = 0xFFF4F6F8.toInt(),
    val customButtonBg: Int = 0xFFFFFFFF.toInt(),
    val customAccent: Int = 0xFF2F7CF6.toInt(),
    val customText: Int = 0xFF1B2430.toInt(),
    val deadzone: Float = 0.08f,
    val hapticsEnabled: Boolean = true,
    val hapticIntensity: Float = 1.0f,
    val alwaysShowPad: Boolean = false,
    val floatingStick: Boolean = false,
    val padScale: Float = 1.0f
) {
    /** Theme hiệu lực: preset theo themeId, hoặc bộ màu tự phối khi themeId = "custom". */
    fun resolveTheme(): PadTheme {
        if (themeId != "custom") return PadThemes.byId(themeId)
        val bg = Color(customPanelBg)
        val btn = Color(customButtonBg)
        val txt = Color(customText)
        return PadTheme(
            id = "custom", name = "Tự phối màu",
            panelBg = bg,
            panelBorder = shade(bg, 0.82f),
            buttonBg = btn,
            buttonPressed = shade(btn, 0.86f),
            buttonText = txt,
            accent = Color(customAccent),
            stickWell = shade(bg, 0.9f),
            stickKnob = btn
        )
    }
}

private val Context.flexpadDataStore by preferencesDataStore(name = "flexpad_settings")

/** Đọc/ghi PadSettings. Mọi ghi đều qua update() để tránh mất trường khi sửa một phần. */
object AppSettings {

    private object Keys {
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_BG = intPreferencesKey("custom_panel_bg")
        val CUSTOM_BUTTON = intPreferencesKey("custom_button_bg")
        val CUSTOM_ACCENT = intPreferencesKey("custom_accent")
        val CUSTOM_TEXT = intPreferencesKey("custom_text")
        val DEADZONE = floatPreferencesKey("deadzone")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val HAPTIC_INTENSITY = floatPreferencesKey("haptic_intensity")
        val ALWAYS_SHOW = booleanPreferencesKey("always_show_pad")
        val FLOATING_STICK = booleanPreferencesKey("floating_stick")
        val PAD_SCALE = floatPreferencesKey("pad_scale")
    }

    fun flow(context: Context): Flow<PadSettings> =
        context.applicationContext.flexpadDataStore.data.map { prefs ->
            PadSettings(
                themeId = prefs[Keys.THEME_ID] ?: "midnight",
                customPanelBg = prefs[Keys.CUSTOM_BG] ?: PadSettings().customPanelBg,
                customButtonBg = prefs[Keys.CUSTOM_BUTTON] ?: PadSettings().customButtonBg,
                customAccent = prefs[Keys.CUSTOM_ACCENT] ?: PadSettings().customAccent,
                customText = prefs[Keys.CUSTOM_TEXT] ?: PadSettings().customText,
                deadzone = prefs[Keys.DEADZONE] ?: 0.08f,
                hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
                hapticIntensity = prefs[Keys.HAPTIC_INTENSITY] ?: 1.0f,
                alwaysShowPad = prefs[Keys.ALWAYS_SHOW] ?: false,
                floatingStick = prefs[Keys.FLOATING_STICK] ?: false,
                padScale = prefs[Keys.PAD_SCALE] ?: 1.0f
            )
        }

    suspend fun current(context: Context): PadSettings = flow(context).first()

    suspend fun update(context: Context, transform: (PadSettings) -> PadSettings) {
        val appCtx = context.applicationContext
        val newValue = transform(current(appCtx))
        appCtx.flexpadDataStore.edit { prefs ->
            prefs[Keys.THEME_ID] = newValue.themeId
            prefs[Keys.CUSTOM_BG] = newValue.customPanelBg
            prefs[Keys.CUSTOM_BUTTON] = newValue.customButtonBg
            prefs[Keys.CUSTOM_ACCENT] = newValue.customAccent
            prefs[Keys.CUSTOM_TEXT] = newValue.customText
            prefs[Keys.DEADZONE] = newValue.deadzone
            prefs[Keys.HAPTICS_ENABLED] = newValue.hapticsEnabled
            prefs[Keys.HAPTIC_INTENSITY] = newValue.hapticIntensity
            prefs[Keys.ALWAYS_SHOW] = newValue.alwaysShowPad
            prefs[Keys.FLOATING_STICK] = newValue.floatingStick
            prefs[Keys.PAD_SCALE] = newValue.padScale
        }
    }
}
