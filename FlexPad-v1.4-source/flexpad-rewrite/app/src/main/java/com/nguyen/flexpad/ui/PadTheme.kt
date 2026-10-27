package com.nguyen.flexpad.ui

import androidx.compose.ui.graphics.Color

/**
 * Bộ màu của mặt tay cầm. 6 theme có sẵn giữ từ bản cũ + "Tự phối màu"
 * (người dùng tự chọn trong Cài đặt, lưu bằng DataStore).
 */
data class PadTheme(
    val id: String,
    val name: String,
    val panelBg: Color,
    val panelBorder: Color,
    val buttonBg: Color,
    val buttonPressed: Color,
    val buttonText: Color,
    val accent: Color,
    val stickWell: Color,
    val stickKnob: Color
)

object PadThemes {
    val Midnight = PadTheme(
        id = "midnight", name = "Midnight",
        panelBg = Color(0xFF14181F), panelBorder = Color(0xFF2A3242),
        buttonBg = Color(0xFF232B38), buttonPressed = Color(0xFF31435F),
        buttonText = Color(0xFFE8EEF7), accent = Color(0xFF4FA3FF),
        stickWell = Color(0xFF0D1015), stickKnob = Color(0xFF2C3646)
    )
    val Sakura = PadTheme(
        id = "sakura", name = "Sakura",
        panelBg = Color(0xFFF7E8EE), panelBorder = Color(0xFFE3B7C6),
        buttonBg = Color(0xFFFFFFFF), buttonPressed = Color(0xFFF3C6D6),
        buttonText = Color(0xFF5A2A3A), accent = Color(0xFFE5638C),
        stickWell = Color(0xFFEFD3DE), stickKnob = Color(0xFFFFFFFF)
    )
    val Ocean = PadTheme(
        id = "ocean", name = "Ocean",
        panelBg = Color(0xFFE6F2F5), panelBorder = Color(0xFFA9CBd6),
        buttonBg = Color(0xFFFFFFFF), buttonPressed = Color(0xFFBFE0EA),
        buttonText = Color(0xFF173F4D), accent = Color(0xFF1E88B8),
        stickWell = Color(0xFFCFE6ED), stickKnob = Color(0xFFFFFFFF)
    )
    val Matcha = PadTheme(
        id = "matcha", name = "Matcha",
        panelBg = Color(0xFFEDF3E4), panelBorder = Color(0xFFBCCBA3),
        buttonBg = Color(0xFFFFFFFF), buttonPressed = Color(0xFFD7E4C0),
        buttonText = Color(0xFF33421F), accent = Color(0xFF6A9A3F),
        stickWell = Color(0xFFDEE9CC), stickKnob = Color(0xFFFFFFFF)
    )
    val Sunset = PadTheme(
        id = "sunset", name = "Sunset",
        panelBg = Color(0xFFFBEEE3), panelBorder = Color(0xFFEBC4A6),
        buttonBg = Color(0xFFFFFFFF), buttonPressed = Color(0xFFF6D3B3),
        buttonText = Color(0xFF59331B), accent = Color(0xFFEF7D3C),
        stickWell = Color(0xFFF3DCC4), stickKnob = Color(0xFFFFFFFF)
    )
    val Mono = PadTheme(
        id = "mono", name = "Mono",
        panelBg = Color(0xFFF2F2F2), panelBorder = Color(0xFFC9C9C9),
        buttonBg = Color(0xFFFFFFFF), buttonPressed = Color(0xFFD9D9D9),
        buttonText = Color(0xFF222222), accent = Color(0xFF555555),
        stickWell = Color(0xFFE2E2E2), stickKnob = Color(0xFFFFFFFF)
    )

    val presets: List<PadTheme> = listOf(Midnight, Sakura, Ocean, Matcha, Sunset, Mono)

    fun byId(id: String): PadTheme = presets.firstOrNull { it.id == id } ?: Midnight
}

/** Làm tối/sáng một màu theo hệ số (0..1 tối hẳn, >1 sáng hơn) — dùng cho chi tiết phụ. */
fun shade(color: Color, factor: Float): Color {
    val f = factor.coerceIn(0f, 2f)
    fun ch(v: Float): Float = (v * f).coerceIn(0f, 1f)
    return Color(red = ch(color.red), green = ch(color.green), blue = ch(color.blue), alpha = color.alpha)
}
