package com.nguyen.flexpad.gamepad

/**
 * Trạng thái tay cầm tại một thời điểm.
 * Trục cần: -32767..32767 (chuẩn hóa từ -1..1 của giao diện).
 * Trigger: 0..1023 (10 bit theo descriptor; giao diện hiện gửi 0 hoặc 1023,
 * cấu trúc đã sẵn sàng cho giá trị analog thật).
 */
data class GamepadState(
    val buttonsMask: Int = 0,
    val hat: Int = HatValue.NEUTRAL,
    val leftX: Int = 0,
    val leftY: Int = 0,
    val rightX: Int = 0,
    val rightY: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0
) {
    fun isNeutral(): Boolean =
        buttonsMask == 0 && hat == HatValue.NEUTRAL &&
            leftX == 0 && leftY == 0 && rightX == 0 && rightY == 0 &&
            leftTrigger == 0 && rightTrigger == 0

    fun withButton(button: PadButton, pressed: Boolean): GamepadState =
        copy(buttonsMask = if (pressed) buttonsMask or button.maskBit else buttonsMask and button.maskBit.inv())

    companion object {
        val NEUTRAL = GamepadState()

        /** Chuẩn hóa giá trị cần -1..1 sang int16, có vùng chết (deadzone) theo tỉ lệ. */
        fun axisFromFloat(v: Float, deadzone: Float = 0.08f): Int {
            val a = kotlin.math.abs(v)
            if (a <= deadzone) return 0
            val scaled = (a - deadzone) / (1f - deadzone)
            val signed = if (v < 0) -scaled else scaled
            return (signed * 32767f).toInt().coerceIn(-32767, 32767)
        }
    }
}
