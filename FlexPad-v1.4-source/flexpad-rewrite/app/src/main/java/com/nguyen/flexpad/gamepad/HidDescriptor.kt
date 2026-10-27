package com.nguyen.flexpad.gamepad

/**
 * HID report descriptor mô phỏng tay cầm Xbox One S (Model 1708) qua Bluetooth,
 * VID 0x045E / PID 0x02FD để Android nạp sẵn file key layout
 * Vendor_045e_Product_02fd.kl của AOSP.
 *
 * ĐÃ ĐỐI CHIẾU với file .kl thật của AOSP (frameworks/base/data/keyboards):
 *  - key 304 BUTTON_A, 305 BUTTON_B, 307 BUTTON_X, 308 BUTTON_Y,
 *    310 BUTTON_L1, 311 BUTTON_R1, 317 BUTTON_THUMBL, 318 BUTTON_THUMBR,
 *    315 BUTTON_START, 316 BUTTON_MODE (và 314 -> BUTTON_SELECT qua Generic.kl).
 *  - axis 0x00 X, 0x01 Y (cần trái); axis 0x02 Z, 0x05 RZ (cần phải);
 *    axis 0x0A (ABS_BRAKE) = LTRIGGER, axis 0x09 (ABS_GAS) = RTRIGGER;
 *    axis 0x10 HAT_X, 0x11 HAT_Y.
 *
 * Từ đó suy ra cách kernel (hid-input, ánh xạ tuần tự cho gamepad: nút n -> BTN_GAMEPAD + n - 1)
 * và Android hiểu descriptor này:
 *  - Cần phải dùng usage Z / Rz (KHÔNG phải Rx / Ry như v0.1 — đó là lỗi của bản cũ).
 *  - Trigger dùng usage Brake / Accelerator của trang Simulation Controls
 *    (KHÔNG phải trục Rx/Ry).
 *  - Thứ tự nút: 1=A, 2=B, 3=đệm, 4=X, 5=Y, 6=đệm, 7=LB, 8=RB, 9-10=đệm,
 *    11=View, 12=Menu, 13=Guide, 14=L3, 15=R3.
 *
 * ĐIỂM CHƯA CHẮC (nói rõ, không bịa): nếu kernel của Find N5 nạp driver hid-microsoft
 * cho VID/PID này thay vì hid-generic, driver đó có thể tự sửa descriptor theo
 * layout gốc của Microsoft. MỐC 2 (panel kiểm thử + getevent -lp) sẽ xác minh thực tế
 * nút nào ra mã nào; nếu lệch, ta sửa tại đây.
 *
 * Cấu trúc báo cáo (Report ID 1, tổng 15 byte):
 *  [0]     = 0x01 (Report ID — bắt buộc đúng, xem ReportEncoder)
 *  [1..2]  = cần trái X (int16 LE), [3..4] = cần trái Y
 *  [5..6]  = cần phải X (usage Z),  [7..8] = cần phải Y (usage Rz)
 *  [9..11] = trigger: Brake 10 bit rồi Accelerator 10 bit, đệm 4 bit
 *  [12..13]= 16 bit nút (bit 0 = nút 1 = A ... bit 14 = nút 15 = R3)
 *  [14]    = hat: 0 = trung tính (Null State), 1..8 = 8 hướng theo chiều kim đồng hồ từ Trên
 */
object HidDescriptor {

    val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        // ---- Collection ứng dụng: Game Pad ----
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x05,             // Usage (Game Pad)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), 0x01,    //   Report ID (1)

        // ---- Hai cần analog (mỗi trục 16 bit, -32767..32767) ----
        0x09, 0x01,             //   Usage (Pointer)
        0xA1.toByte(), 0x00,    //   Collection (Physical)
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)  — cần trái X
        0x09, 0x31,             //     Usage (Y)  — cần trái Y
        0x09, 0x32,             //     Usage (Z)  — cần phải X (theo file .kl: axis 0x02)
        0x09, 0x35,             //     Usage (Rz) — cần phải Y (theo file .kl: axis 0x05)
        0x16, 0x01, 0x80.toByte(), //  Logical Minimum (-32767)
        0x26, 0xFF.toByte(), 0x7F, //  Logical Maximum (32767)
        0x75, 0x10,             //     Report Size (16)
        0x95.toByte(), 0x04,    //     Report Count (4)
        0x81.toByte(), 0x02,    //     Input (Data, Var, Abs)
        0xC0.toByte(),          //   End Collection

        // ---- Trigger analog: Brake (trái) + Accelerator (phải), 10 bit mỗi cái ----
        0x05, 0x02,             //   Usage Page (Simulation Controls)
        0x09, 0xC5.toByte(),    //   Usage (Brake)       -> ABS_BRAKE  -> LTRIGGER
        0x09, 0xC4.toByte(),    //   Usage (Accelerator) -> ABS_GAS    -> RTRIGGER
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x03, // Logical Maximum (1023)
        0x75, 0x0A,             //   Report Size (10)
        0x95.toByte(), 0x02,    //   Report Count (2)
        0x81.toByte(), 0x02,    //   Input (Data, Var, Abs)
        0x75, 0x01,             //   Report Size (1)  — đệm 4 bit cho tròn 3 byte
        0x95.toByte(), 0x04,    //   Report Count (4)
        0x81.toByte(), 0x03,    //   Input (Const, Var, Abs)

        // ---- 15 nút trong trường 16 bit (có bit đệm đúng vị trí) ----
        0x05, 0x09,             //   Usage Page (Button)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x19, 0x01,             //   Usage Minimum (1)
        0x29, 0x02,             //   Usage Maximum (2)   -> nút 1=A, 2=B
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x02,    //   Report Count (2)
        0x81.toByte(), 0x02,    //   Input (Data, Var, Abs)
        0x95.toByte(), 0x01,    //   Report Count (1)    -> đệm nút 3
        0x81.toByte(), 0x03,    //   Input (Const, Var, Abs)
        0x19, 0x04,             //   Usage Minimum (4)
        0x29, 0x05,             //   Usage Maximum (5)   -> nút 4=X, 5=Y
        0x95.toByte(), 0x02,    //   Report Count (2)
        0x81.toByte(), 0x02,    //   Input (Data, Var, Abs)
        0x95.toByte(), 0x01,    //   Report Count (1)    -> đệm nút 6
        0x81.toByte(), 0x03,    //   Input (Const, Var, Abs)
        0x19, 0x07,             //   Usage Minimum (7)
        0x29, 0x08,             //   Usage Maximum (8)   -> nút 7=LB, 8=RB
        0x95.toByte(), 0x02,    //   Report Count (2)
        0x81.toByte(), 0x02,    //   Input (Data, Var, Abs)
        0x95.toByte(), 0x02,    //   Report Count (2)    -> đệm nút 9, 10
        0x81.toByte(), 0x03,    //   Input (Const, Var, Abs)
        0x19, 0x0B,             //   Usage Minimum (11)
        0x29, 0x0F,             //   Usage Maximum (15)  -> 11=View, 12=Menu, 13=Guide, 14=L3, 15=R3
        0x95.toByte(), 0x05,    //   Report Count (5)
        0x81.toByte(), 0x02,    //   Input (Data, Var, Abs)
        0x95.toByte(), 0x01,    //   Report Count (1)    -> đệm đủ 16 bit
        0x81.toByte(), 0x03,    //   Input (Const, Var, Abs)

        // ---- Hat switch cho D-pad: 8 bit, có Null State ----
        0x05, 0x01,             //   Usage Page (Generic Desktop)
        0x09, 0x39,             //   Usage (Hat switch)
        0x15, 0x01,             //   Logical Minimum (1)
        0x25, 0x08,             //   Logical Maximum (8)
        0x35, 0x00,             //   Physical Minimum (0)
        0x46, 0x3B, 0x01,       //   Physical Maximum (315)
        0x65, 0x14,             //   Unit (Degrees)
        0x75, 0x08,             //   Report Size (8)
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x81.toByte(), 0x42,    //   Input (Data, Var, Abs, Null State) — giá trị 0 = trung tính
        0xC0.toByte()           // End Collection
    )

    /** Tổng độ dài báo cáo input (kể cả byte Report ID). */
    const val REPORT_SIZE = 15

    /** Report ID duy nhất của thiết bị. */
    const val REPORT_ID: Byte = 0x01
}
