package com.nguyen.flexpad.gamepad

/**
 * Hằng số giao thức UHID — đã đối chiếu trực tiếp với include/uapi/linux/uhid.h
 * của kernel Linux (bản master) ngày 2026-10-26.
 *
 * BÀI HỌC TỪ v0.1: bản cũ dùng dãy số bắt đầu từ 0 (CREATE2 = 9, INPUT2 = 11) — SAI.
 * Enum thật bắt đầu bằng __UHID_LEGACY_CREATE = 0 nên mọi giá trị bị lệch 1;
 * thứ viết ra với type = 9 thực chất là UHID_GET_REPORT (sự kiện kernel -> userspace)
 * và bị kernel từ chối. Tuyệt đối không dùng "số trần", chỉ dùng các hằng số có tên ở đây.
 */
object Uhid {
    // Loại sự kiện (u32 little-endian ở đầu struct uhid_event).
    const val UHID_DESTROY = 1
    const val UHID_START = 2
    const val UHID_STOP = 3
    const val UHID_OPEN = 4
    const val UHID_CLOSE = 5
    const val UHID_OUTPUT = 6
    const val UHID_GET_REPORT = 9
    const val UHID_GET_REPORT_REPLY = 10
    const val UHID_CREATE2 = 11
    const val UHID_INPUT2 = 12
    const val UHID_SET_REPORT = 13
    const val UHID_SET_REPORT_REPLY = 14

    /**
     * sizeof(struct uhid_event) = 4 (type) + sizeof(uhid_create2_req lớn nhất trong union).
     * uhid_create2_req = name[128] + phys[64] + uniq[64] + rd_size(2) + bus(2)
     *                  + vendor(4) + product(4) + version(4) + country(4)
     *                  + rd_data[HID_MAX_DESCRIPTOR_SIZE = 4096] = 4372.
     * Tổng = 4376 byte. (Con số 4380 trong đặc tả ban đầu chênh 4 byte;
     * đã kiểm chứng lại bằng hai header ở trên. Hơn nữa, theo chính comment trong
     * uhid.h: sự kiện ghi ngắn hơn sẽ được kernel tự đệm 0, và mỗi lần write()
     * chỉ xử lý đúng một sự kiện — ghi đủ 4376 byte là đúng chuẩn.)
     */
    const val EVENT_SIZE = 4376

    // Các offset trong struct uhid_create2_req (tính từ sau trường type).
    const val CREATE2_NAME_OFF = 4
    const val CREATE2_PHYS_OFF = CREATE2_NAME_OFF + 128        // 132
    const val CREATE2_UNIQ_OFF = CREATE2_PHYS_OFF + 64         // 196
    const val CREATE2_RD_SIZE_OFF = CREATE2_UNIQ_OFF + 64      // 260
    const val CREATE2_BUS_OFF = CREATE2_RD_SIZE_OFF + 2        // 262
    const val CREATE2_VENDOR_OFF = CREATE2_BUS_OFF + 2         // 264
    const val CREATE2_PRODUCT_OFF = CREATE2_VENDOR_OFF + 4     // 268
    const val CREATE2_VERSION_OFF = CREATE2_PRODUCT_OFF + 4    // 272
    const val CREATE2_COUNTRY_OFF = CREATE2_VERSION_OFF + 4    // 276
    const val CREATE2_RD_DATA_OFF = CREATE2_COUNTRY_OFF + 4    // 280

    // UHID_INPUT2: size (u16) tại offset 4, data bắt đầu tại offset 6.
    const val INPUT2_SIZE_OFF = 4
    const val INPUT2_DATA_OFF = 6

    // UHID_GET_REPORT: id (u32) tại 4, rnum (u8) tại 8, rtype (u8) tại 9.
    const val GET_REPORT_ID_OFF = 4
    const val GET_REPORT_RNUM_OFF = 8
    const val GET_REPORT_RTYPE_OFF = 9

    // UHID_GET_REPORT_REPLY: id (u32) tại 4, err (u16) tại 8, size (u16) tại 10, data tại 12.
    const val GET_REPORT_REPLY_DATA_OFF = 12

    // UHID_SET_REPORT: id (u32) tại 4, rnum (u8) tại 8, rtype (u8) tại 9, size (u16) tại 10, data tại 12.
    const val SET_REPORT_SIZE_OFF = 10
    const val SET_REPORT_DATA_OFF = 12

    // UHID_OUTPUT: data[4096] tại 4, size (u16) tại 4100, rtype (u8) tại 4102.
    const val OUTPUT_SIZE_OFF = 4100
    const val OUTPUT_RTYPE_OFF = 4102

    // Loại report trong UHID (enum uhid_report_type).
    const val REPORT_TYPE_FEATURE = 0
    const val REPORT_TYPE_OUTPUT = 1
    const val REPORT_TYPE_INPUT = 2

    // Thông tin định danh thiết bị.
    const val BUS_BLUETOOTH = 0x05
    const val VENDOR_MICROSOFT = 0x045E
    const val PRODUCT_XBOX_WIRELESS = 0x02FD
    const val DEVICE_NAME = "Xbox Wireless Controller"

    /** Tên sự kiện theo số — dùng khi in nhật ký chẩn đoán. */
    fun eventName(type: Int): String = when (type) {
        UHID_DESTROY -> "UHID_DESTROY"
        UHID_START -> "UHID_START"
        UHID_STOP -> "UHID_STOP"
        UHID_OPEN -> "UHID_OPEN"
        UHID_CLOSE -> "UHID_CLOSE"
        UHID_OUTPUT -> "UHID_OUTPUT"
        UHID_GET_REPORT -> "UHID_GET_REPORT"
        UHID_GET_REPORT_REPLY -> "UHID_GET_REPORT_REPLY"
        UHID_CREATE2 -> "UHID_CREATE2"
        UHID_INPUT2 -> "UHID_INPUT2"
        UHID_SET_REPORT -> "UHID_SET_REPORT"
        UHID_SET_REPORT_REPLY -> "UHID_SET_REPORT_REPLY"
        0 -> "__UHID_LEGACY_CREATE"
        7 -> "__UHID_LEGACY_OUTPUT_EV"
        8 -> "__UHID_LEGACY_INPUT"
        else -> "KHÔNG RÕ ($type)"
    }
}
