package com.nguyen.flexpad.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic cho tay cầm ảo — mục tiêu là cảm giác "phím thật", học từ lỗi của v0.1
 * (chỉ rung một lần đúng lúc đăng ký input nên rất giả tạo).
 *
 * Nhịp 3 giai đoạn cho mỗi lần bấm (giống bản v0.2 đã kiểm chứng cảm giác tốt hơn):
 *  1) chạm nhẹ (tick yếu) ngay khi ngón tay chạm mặt nút — giai đoạn di chuyển phím;
 *  2) "tách" khi đạt điểm kích hoạt (~38ms) — CLICK mạnh + THUD trầm, đồng thời
 *     mới là lúc input được đăng ký (PadPressable chịu trách nhiệm gọi đúng thứ tự);
 *  3) nảy về (QUICK_RISE nhẹ) khi nhả tay.
 * Cần analog: tick nhẹ khi qua mỗi nấc 45° ở vành ngoài, click nhẹ khi về tâm.
 * Rumble từ game (UHID OUTPUT) được phát qua rumble().
 *
 * Cường độ tổng thể chỉnh được bằng [intensity] (0.3..1.5) và tắt hẳn bằng [enabled];
 * hai giá trị này được đồng bộ từ AppSettings tại MainActivity và PadOverlayService.
 */
object ButtonHaptics {

    @Volatile
    private var vibrator: Vibrator? = null

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var intensity: Float = 1.0f

    fun init(context: Context) {
        if (vibrator != null) return
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    private fun scale(base: Float): Float =
        (base * intensity).coerceIn(0.05f, 1.0f)

    /** Phát một tổ hợp primitive; thiết bị không hỗ trợ thì dự phòng rung một nhịp ngắn. */
    private fun play(build: (VibrationEffect.Composition) -> VibrationEffect.Composition, fallbackMs: Long) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val effect = build(VibrationEffect.startComposition()).compose()
            v.vibrate(effect)
        } catch (e: Exception) {
            // Dự phòng có chủ đích: máy không hỗ trợ composition primitives.
            try {
                v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (ignored: Exception) {
                // Haptic là phụ trợ cảm giác — bỏ qua nếu cả dự phòng cũng lỗi.
            }
        }
    }

    /** Giai đoạn 1: ngón tay vừa chạm, phím bắt đầu di chuyển. */
    fun touchTick() = play({ c ->
        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale(0.5f), 0)
    }, 8)

    /** Giai đoạn 2: chạm điểm kích hoạt — input được đăng ký tại đây. */
    fun actuation() = play({ c ->
        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale(1.0f), 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale(0.6f), 0)
    }, 18)

    /** Giai đoạn 3: nhả tay, phím nảy về. */
    fun release() = play({ c ->
        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, scale(0.3f), 0)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale(0.4f), 0)
    }, 10)

    /** Cần analog chạm vành ở một nấc hướng mới. */
    fun stickDetent() = play({ c ->
        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale(0.35f), 0)
    }, 6)

    /** Cần analog về đúng tâm khi thả. */
    fun stickCenter() = play({ c ->
        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale(0.4f), 0)
    }, 10)

    /** Rumble từ game: magnitude 0..255 -> biên độ rung trong ~130ms. */
    fun rumble(magnitude: Int) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator() || magnitude <= 0) return
        try {
            if (v.hasAmplitudeControl()) {
                val amp = (magnitude * intensity).toInt().coerceIn(1, 255)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 130), intArrayOf(amp, 0), -1))
            } else {
                v.vibrate(VibrationEffect.createOneShot(130, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (ignored: Exception) {
            // Rumble là phụ trợ — bỏ qua lỗi phát rung.
        }
    }
}
