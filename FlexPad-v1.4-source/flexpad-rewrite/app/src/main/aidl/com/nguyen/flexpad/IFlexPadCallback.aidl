package com.nguyen.flexpad;

// Callback từ UserService về app (hiện dùng cho rumble từ game qua UHID OUTPUT).
interface IFlexPadCallback {

    // Độ mạnh rung trái/phải (0..255) do game gửi xuống tay cầm.
    void onRumble(int leftMagnitude, int rightMagnitude) = 1;
}
