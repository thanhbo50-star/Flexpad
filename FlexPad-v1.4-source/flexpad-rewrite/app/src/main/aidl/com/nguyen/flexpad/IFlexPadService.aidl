package com.nguyen.flexpad;

import com.nguyen.flexpad.IFlexPadCallback;

// Giao diện AIDL giữa app FlexPad và tiến trình Shizuku UserService.
// Mọi hàm chẩn đoán / tạo thiết bị đều trả về CHUỖI NHẬT KÝ CHI TIẾT từng bước
// (kèm errno thật) để hiển thị lên màn hình — tuyệt đối không nuốt lỗi.
interface IFlexPadService {

    // Shizuku yêu cầu hàm destroy() với mã giao dịch cố định này để dọn tiến trình service.
    // LƯU Ý cú pháp AIDL: đã gán mã cho một hàm thì PHẢI gán cho mọi hàm
    // (aidl báo lỗi "You must either assign id's to all methods or to none"),nên các hàm dưới đều có mã riêng.
    void destroy() = 16777114;

    // Thoát tiến trình service ngay lập tức.
    void exit() = 1;

    // Kiểm tra service còn sống. Trả về "flexpad-pad-ok".
    String ping() = 2;

    // App gửi đường dẫn thư viện native (để service nạp libflexpad_uinput.so cho backend B2).
    void initPaths(String nativeLibDir) = 3;

    // Thu thập toàn bộ báo cáo chẩn đoán (mục 3.1 của đặc tả) và trả về dạng text.
    String runDiagnostics() = 4;

    // Tạo thiết bị tay cầm ảo.
    // backend: 0 = tự động thử B1 (UHID) rồi B2 (uinput); 1 = chỉ B1; 2 = chỉ B2.
    // Trả về nhật ký từng bước kèm kết quả kiểm chứng bằng dumpsys input.
    String createDevice(int backend) = 5;

    // Hủy thiết bị đang hoạt động. Trả về nhật ký.
    String destroyDevice() = 6;

    // Thiết bị đã được tạo và xác minh chưa.
    boolean isDeviceCreated() = 7;

    // Tên backend đang hoạt động: "UHID", "UINPUT" hoặc "" nếu chưa có.
    String activeBackend() = 8;

    // Gửi trạng thái tay cầm (gói 15 byte do ReportEncoder đóng gói).
    // oneway + chỉ gọi khi trạng thái thay đổi, tối đa ~250 lần/giây (xem PadController).
    oneway void sendState(in byte[] packedState) = 9;

    // Backend B4: thử tiêm một lần bấm nút A qua IInputManager.injectInputEvent.
    // Trả về nhật ký chi tiết (kèm giá trị trả về của injectInputEvent).
    String testInjectButtonA() = 10;

    // Nhật ký sự kiện kernel gần đây (UHID START/OPEN/GET_REPORT/OUTPUT...) để chẩn đoán.
    String eventLog() = 11;

    // Cửa sổ đang có focus theo dumpsys window (mCurrentFocus / mFocusedApp) —
    // dùng để kiểm chứng overlay pad không giành focus của game (MỐC 3).
    String windowFocus() = 12;

    // Đăng ký callback nhận sự kiện từ service về app (rumble).
    void registerCallback(IFlexPadCallback callback) = 13;

    // Hủy đăng ký callback.
    void unregisterCallback() = 14;

    // Bật hỗ trợ cửa sổ tự do (freeform) cho phương án đưa game lên nửa trên:
    // settings put global enable_freeform_support / force_resizable_activities.
    // Trả về nhật ký từng lệnh.
    String applyFreeformSettings() = 15;
}
