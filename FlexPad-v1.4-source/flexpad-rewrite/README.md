Ứng dụng Android biến nửa dưới màn hình OPPO Find N5 (khi gập Flex Mode ~90°) thành tay cầm Xbox ảo mà game nhận như tay cầm Bluetooth thật. Thiết bị ảo được tạo qua Shizuku bằng UHID (B1) hoặc uinput (B2); không dùng mô phỏng cảm ứng.## Cấu trúc app (4 tab)
- **Chính**: trạng thái Shizuku, bắt đầu/dừng phiên tay cầm, tư thế gập trực tiếp (kèm bounds bản lề và góc bản lề).
- **Kiểm thử**: mặt pad đầy đủ + bảng đọc trạng thái nút/trục trực tiếp, để đối chiếu với trang kiểm tra gamepad.
- **Cài đặt**: quyền overlay, luôn hiện pad, cỡ pad, deadzone, chế độ tâm cần, haptic, theme (6 preset + tự phối màu), công cụ freeform.
- **Chẩn đoán**: báo cáo hệ thống 8 mục (uid/SELinux, /dev/uhid, /dev/uinput, errno, kernel config, avc denied, dumpsys input, Shizuku), tạo/hủy thiết bị, thử backend B4, nhật ký sự kiện kernel, kiểm chứng focus.## Build APK
Cách 1 — GitHub Actions (không cần Android Studio): đẩy thư mục này lên repo GitHub (nhánh `main`) → tab **Actions** → workflow *Build FlexPad APK* → tải artifact **FlexPad-debug-apk** → `app-debug.apk`.
Cách 2 — máy có Android SDK/NDK: `gradle assembleDebug` (cần JDK 17, SDK platform 35, build-tools 35.0.0, NDK 26.1.10909125, CMake 3.22.1).
APK debug cài đè được lên các bản FlexPad trước (versionCode tăng dần).## Bật Shizuku
1. Cài app **Shizuku**, bật **Tùy chọn nhà phát triển** (chạm Số bản dựng 7 lần).
2. Bật **Gỡ lỗi USB** + **Gỡ lỗi không dây**; máy ColorOS: nếu Shizuku báo lỗi quyền, tắt **"Giám sát quyền"** (*Disable permission monitoring*) ở gần cuối Tùy chọn nhà phát triển.
3. Mở Shizuku → khởi động qua Gỡ lỗi không dây (nhập mã ghép nối 6 số).
4. Trong FlexPad (tab Chính): **Xin quyền Shizuku** → **Kết nối UserService**.
Lưu ý: Shizuku mất hiệu lực sau mỗi lần khởi động lại máy — phải khởi động lại. FlexPad dùng `daemon(true)` nên tiến trình phụ không chết khi app bị đóng; muốn tắt hẳn thì bấm **Dừng phiên**.## Quy trình chơi game
1. Tab Chẩn đoán → **Tạo thiết bị** (Tự động B1→B2). Chỉ khi nhật ký kết thúc "THÀNH CÔNG ... đã kiểm chứng bằng dumpsys input" mới là có tay cầm thật.
2. Tab Chính → **Bắt đầu phiên** (Foreground Service; cấp quyền "Hiển thị trên các ứng dụng khác" ở tab Cài đặt trước).
3. Mở game (Genshin: bật Controller trong Cài đặt của game; ZZZ: chọn UI Layout = Controller).
4. Gập máy ~90° (Flex Mode): pad tự hiện đúng vùng dưới bản lề. Pad là cửa sổ overlay **không giành focus** — kiểm chứng ở cuối tab Chẩn đoán (mCurrentFocus phải là game sau khi chạm pad).
5. Game ở nửa trên: thử lần lượt chia màn hình của ColorOS → freeform (nút trong Cài đặt) → để game toàn màn hình, pad phủ nửa dưới. Honkai: Star Rail trên Android **không hỗ trợ tay cầm** nên không dùng được với FlexPad.## Giữ app sống trên ColorOS
- Tắt tối ưu pin cho FlexPad và Shizuku (Pin → Không hạn chế).
- Khóa cả hai app trong màn hình đa nhiệm (ColorOS hay kill nền).
- Bật thông báo cho FlexPad để thấy phiên đang chạy.## Đọc báo cáo chẩn đoán khi lỗi
Chạy **Chẩn đoán** rồi **Tạo thiết bị**; nếu thất bại, bấm **Sao chép báo cáo** + **Sao chép nhật ký thao tác** và gửi lại cả hai. Ba dữ kiện quyết định: errno ở bước mở `/dev/uhid` (mục 3), dòng `avc: denied` (mục 6), và việc dumpsys input có thấy "Xbox Wireless Controller" hay không.
Backend B4 chỉ tiêm sự kiện với deviceId ảo — nhiều game bỏ qua; đó là giới hạn đã biết, không phải tay cầm thật. B5 (VirtualDeviceManager) không khả thi trên Android 15 cho app thường — xem `backend/InputBackend.kt`.## Trạng thái các mốc
MỐC 1 chẩn đoán + tạo thiết bị (B1/B2/B4, kiểm chứng dumpsys) — có. MỐC 2 panel kiểm thử — có. MỐC 3 overlay không giành focus + kiểm chứng mCurrentFocus — có. MỐC 4 Flex Mode theo bounds bản lề + overlay bám nửa dưới + freeform — có. MỐC 5 theme/tự phối màu, haptic 3 giai đoạn, rumble best-effort từ UHID OUTPUT, deadzone, lưu cấu hình DataStore, chỉnh cỡ pad — có.
