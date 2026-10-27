package com.nguyen.flexpad.gamepad

/**
 * Đóng gói / giải gói trạng thái tay cầm.
 *
 * Hai định dạng:
 *  1) Gói trạng thái qua AIDL (15 byte, < 20 byte theo yêu cầu độ trễ của đặc tả):
 *     [0..1] mặt nạ nút u16 LE, [2] hat, [3..4] LX i16 LE, [5..6] LY,
 *     [7..8] RX, [9..10] RY, [11..12] LT u16 (0..1023), [13..14] RT u16.
 *  2) Báo cáo HID đầy đủ (15 byte) theo HidDescriptor, dùng cho backend UHID:
 *     [0] Report ID = 1, sau đó 4 trục 16 bit, trigger đóng gói 10+10 bit,
 *     16 bit nút, 1 byte hat.
 * Backend uinput (JNI) đọc trực tiếp gói (1); backend UHID chuyển (1) -> (2) tại đây.
 */
object ReportEncoder {

    const val PACKET_SIZE = 15

    private fun putI16(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun getI16(b: ByteArray, off: Int): Int {
        val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun getU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** GamepadState -> gói AIDL 15 byte. */
    fun encodeStatePacket(state: GamepadState): ByteArray {
        val out = ByteArray(PACKET_SIZE)
        putI16(out, 0, state.buttonsMask and 0xFFFF)
        out[2] = (state.hat and 0xFF).toByte()
        putI16(out, 3, state.leftX)
        putI16(out, 5, state.leftY)
        putI16(out, 7, state.rightX)
        putI16(out, 9, state.rightY)
        putI16(out, 11, state.leftTrigger and 0x3FF)
        putI16(out, 13, state.rightTrigger and 0x3FF)
        return out
    }

    /** Gói AIDL 15 byte -> GamepadState. */
    fun decodeStatePacket(packet: ByteArray): GamepadState {
        require(packet.size >= PACKET_SIZE) { "Gói trạng thái quá ngắn: ${packet.size} byte" }
        return GamepadState(
            buttonsMask = getU16(packet, 0),
            hat = packet[2].toInt() and 0xFF,
            leftX = getI16(packet, 3),
            leftY = getI16(packet, 5),
            rightX = getI16(packet, 7),
            rightY = getI16(packet, 9),
            leftTrigger = getU16(packet, 11) and 0x3FF,
            rightTrigger = getU16(packet, 13) and 0x3FF
        )
    }

    /**
     * GamepadState -> báo cáo HID 15 byte cho UHID_INPUT2.
     * Byte đầu LUÔN là Report ID đã khai báo trong descriptor (yêu cầu ở phụ lục, mục 5).
     * Trigger đóng gói: byte 9 = 8 bit thấp của Brake; byte 10 = 2 bit cao của Brake
     * + 6 bit thấp của Accelerator dịch trái 2; byte 11 = 4 bit cao của Accelerator.
     */
    fun encodeHidReport(state: GamepadState): ByteArray {
        val out = ByteArray(HidDescriptor.REPORT_SIZE)
        out[0] = HidDescriptor.REPORT_ID
        putI16(out, 1, state.leftX)
        putI16(out, 3, state.leftY)
        putI16(out, 5, state.rightX)
        putI16(out, 7, state.rightY)
        val brake = state.leftTrigger and 0x3FF
        val accel = state.rightTrigger and 0x3FF
        out[9] = (brake and 0xFF).toByte()
        out[10] = (((brake shr 8) and 0x03) or ((accel and 0x3F) shl 2)).toByte()
        out[11] = ((accel shr 6) and 0x0F).toByte()
        putI16(out, 12, state.buttonsMask and 0xFFFF)
        out[14] = (state.hat and 0xFF).toByte()
        return out
    }
}
