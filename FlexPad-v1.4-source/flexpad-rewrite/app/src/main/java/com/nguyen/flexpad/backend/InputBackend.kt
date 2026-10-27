package com.nguyen.flexpad.backend

import com.nguyen.flexpad.gamepad.GamepadState

/** Định danh các backend tạo thiết bị / gửi input. */
enum class BackendId(val label: String) {
    UHID("B1 — UHID (/dev/uhid)"),
    UINPUT("B2 — uinput (/dev/uinput, native JNI)"),
    INJECT("B4 — injectInputEvent (chỉ tiêm sự kiện, không tạo thiết bị)")
}

/**
 * Hợp đồng chung cho backend tạo tay cầm ảo.
 * Tất cả backend đều chạy TRONG tiến trình Shizuku UserService (uid shell/root) —
 * file descriptor không bao giờ được truyền về tiến trình app (SELinux có thể chặn
 * việc ghi từ fd nhận qua binder, và đặc tả cũng yêu cầu như vậy).
 *
 * Ghi chú nghiên cứu B5 (VirtualDeviceManager và các hidden API khác trên Android 15):
 *  - VirtualDeviceManager (Android 14+) cho phép app TẠO thiết bị ảo, nhưng các API
 *    tạo thiết bị đầu vào (createVirtualKeyboard/Mouse/Touchscreen...) nằm trên
 *    VirtualDevice, yêu cầu quyền CREATE_VIRTUAL_DEVICE (signature/privileged) và
 *    thiết bị ảo chỉ thuộc về "virtual device" của chính app đó — không tạo được
 *    một gamepad hệ thống mà game khác nhìn thấy như tay cầm thật.
 *  - Vì vậy B5 KHÔNG khả thi cho tiến trình shell của Shizuku trên Android 15.
 *    Kết luận: UHID (B1) và uinput (B2) là hai con đường đúng; B4 chỉ là dự phòng
 *    chất lượng thấp (deviceId ảo, nhiều game bỏ qua).
 */
interface InputBackend {
    val id: BackendId

    /**
     * Tạo thiết bị. Trả về nhật ký từng bước bằng tiếng Việt, kèm errno thật.
     * Chỉ coi là thành công sau khi tầng gọi đã kiểm chứng bằng dumpsys input.
     */
    fun create(): String

    /** Gửi một trạng thái tay cầm. Trả về false nếu ghi lỗi (lỗi được ghi vào nhật ký sự kiện). */
    fun sendState(state: GamepadState): Boolean

    /** Hủy thiết bị. Trả về nhật ký. */
    fun destroy(): String

    /** Thiết bị đã tạo (chưa tính kiểm chứng dumpsys ở tầng gọi). */
    fun isCreated(): Boolean
}
