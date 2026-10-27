package com.nguyen.flexpad.gamepad

/**
 * Danh sách nút của tay cầm.
 * [hidButtonIndex] là số thứ tự nút trong HID descriptor (1..15) — quyết định
 * mã phím Linux theo ánh xạ tuần tự của hid-input và file .kl của AOSP:
 *   1=A, 2=B, 4=X, 5=Y, 7=LB, 8=RB, 11=View, 12=Menu, 13=Guide, 14=L3, 15=R3.
 * Bit trong mặt nạ nút = hidButtonIndex - 1.
 */
enum class PadButton(val hidButtonIndex: Int, val label: String) {
    A(1, "A"),
    B(2, "B"),
    X(4, "X"),
    Y(5, "Y"),
    LB(7, "LB"),
    RB(8, "RB"),
    VIEW(11, "View"),
    MENU(12, "Menu"),
    GUIDE(13, "Xbox"),
    L3(14, "L3"),
    R3(15, "R3");

    /** Bit tương ứng trong mặt nạ 16 bit của báo cáo. */
    val maskBit: Int get() = 1 shl (hidButtonIndex - 1)
}

/** Hướng D-pad dạng hat: 0 = trung tính, 1..8 theo chiều kim đồng hồ bắt đầu từ Trên. */
object HatValue {
    const val NEUTRAL = 0
    const val UP = 1
    const val UP_RIGHT = 2
    const val RIGHT = 3
    const val DOWN_RIGHT = 4
    const val DOWN = 5
    const val DOWN_LEFT = 6
    const val LEFT = 7
    const val UP_LEFT = 8

    /** Tính giá trị hat từ 4 hướng rời rạc của D-pad. */
    fun fromDirections(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Int = when {
        up && right -> UP_RIGHT
        down && right -> DOWN_RIGHT
        down && left -> DOWN_LEFT
        up && left -> UP_LEFT
        up -> UP
        right -> RIGHT
        down -> DOWN
        left -> LEFT
        else -> NEUTRAL
    }
}
