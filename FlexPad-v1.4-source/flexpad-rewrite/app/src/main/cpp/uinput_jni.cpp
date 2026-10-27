// FlexPad — backend B2: tạo tay cầm ảo qua /dev/uinput bằng mã native (JNI).
// Lý do cần native: android.system.Os không exposing ioctl cho uinput trong SDK công khai.
// File này chạy trong tiến trình Shizuku UserService (uid shell 2000 hoặc root 0).
// Mọi bước đều ghi nhật ký kèm errno + strerror để Kotlin hiển thị lên màn hình chẩn đoán.

#include <jni.h>
#include <string>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/uinput.h>

namespace {

// Descriptor của tay cầm: trùng khớp với backend UHID (B1).
const int kVendor = 0x045E;   // Microsoft
const int kProduct = 0x02FD;  // Xbox Wireless Controller (One S, Bluetooth)
const char* kName = "Xbox Wireless Controller";

// fd của /dev/uinput sau khi tạo thiết bị thành công; -1 = chưa tạo.
int g_fd = -1;

// Trạng thái gần nhất để chỉ gửi sự kiện khi có thay đổi (giống gói 15 byte của ReportEncoder).
uint16_t g_lastButtons = 0;
int g_lastHat = 0;
int g_lastAxes[6] = {0, 0, 0, 0, 0, 0};  // LX, LY, RX, RY, LT, RT

void appendLog(std::string& log, const std::string& line) {
    log += line;
    log += "\n";
}

// Ghi một sự kiện evdev vào buffer (chưa flush).
void pushEvent(input_event* buf, int& n, uint16_t type, uint16_t code, int32_t value) {
    input_event& ev = buf[n++];
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
}

// Gửi toàn bộ buffer xuống kernel trong một lần write; trả về false nếu write lỗi.
bool flushEvents(const input_event* buf, int n, std::string& err) {
    size_t bytes = sizeof(input_event) * (size_t)n;
    ssize_t written = write(g_fd, buf, bytes);
    if (written < 0 || (size_t)written != bytes) {
        err = "write input_event lỗi: errno=" + std::to_string(errno) + " (" + strerror(errno) + ")";
        return false;
    }
    return true;
}

// Ánh xạ bit nút trong gói trạng thái -> mã BTN_* của Linux.
// Thứ tự bit = số thứ tự nút HID - 1 (xem HidDescriptor): 1=A, 2=B, 4=X, 5=Y,
// 7=LB, 8=RB, 11=View, 12=Menu, 13=Guide, 14=L3, 15=R3. Các bit còn lại là đệm.
int buttonCodeForBit(int bit) {
    switch (bit) {
        case 0: return BTN_SOUTH;   // A
        case 1: return BTN_EAST;    // B
        case 3: return BTN_NORTH;   // X
        case 4: return BTN_WEST;    // Y
        case 6: return BTN_TL;      // LB
        case 7: return BTN_TR;      // RB
        case 10: return BTN_SELECT; // View
        case 11: return BTN_START;  // Menu
        case 12: return BTN_MODE;   // Guide/Xbox
        case 13: return BTN_THUMBL; // L3
        case 14: return BTN_THUMBR; // R3
        default: return -1;
    }
}

}  // namespace

extern "C" {

// Tạo thiết bị uinput. Trả về nhật ký từng bước (chuỗi), thành công khi dòng cuối bắt đầu bằng "OK".
JNIEXPORT jstring JNICALL
Java_com_nguyen_flexpad_backend_UinputNative_nativeCreate(JNIEnv* env, jclass /* clazz */) {
    std::string log;
    if (g_fd >= 0) {
        appendLog(log, "OK: thiết bị uinput đã tồn tại từ trước (fd=" + std::to_string(g_fd) + ")");
        return env->NewStringUTF(log.c_str());
    }

    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        appendLog(log, "LỖI mở /dev/uinput: errno=" + std::to_string(errno) + " (" + strerror(errno) + ")");
        appendLog(log, "KẾT QUẢ: THẤT BẠI ở bước mở /dev/uinput");
        return env->NewStringUTF(log.c_str());
    }
    appendLog(log, "Mở /dev/uinput thành công (fd=" + std::to_string(fd) + ")");

    // Khai báo loại sự kiện: phím + trục tuyệt đối.
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) {
        appendLog(log, "LỖI UI_SET_EVBIT: errno=" + std::to_string(errno) + " (" + strerror(errno) + ")");
        close(fd);
        appendLog(log, "KẾT QUẢ: THẤT BẠI ở bước UI_SET_EVBIT");
        return env->NewStringUTF(log.c_str());
    }
    appendLog(log, "UI_SET_EVBIT (EV_KEY, EV_ABS): OK");

    // Khai báo từng phím.
    const int keys[] = {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR,
                        BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (int key : keys) {
        if (ioctl(fd, UI_SET_KEYBIT, key) < 0) {
            appendLog(log, "LỖI UI_SET_KEYBIT mã " + std::to_string(key) + ": errno=" +
                             std::to_string(errno) + " (" + strerror(errno) + ")");
            close(fd);
            appendLog(log, "KẾT QUẢ: THẤT BẠI ở bước UI_SET_KEYBIT");
            return env->NewStringUTF(log.c_str());
        }
    }
    appendLog(log, "UI_SET_KEYBIT (11 phím): OK");

    // Khai báo từng trục.
    const int absCodes[] = {ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_BRAKE, ABS_GAS, ABS_HAT0X, ABS_HAT0Y};
    for (int code : absCodes) {
        if (ioctl(fd, UI_SET_ABSBIT, code) < 0) {
            appendLog(log, "LỖI UI_SET_ABSBIT mã " + std::to_string(code) + ": errno=" +
                             std::to_string(errno) + " (" + strerror(errno) + ")");
            close(fd);
            appendLog(log, "KẾT QUẢ: THẤT BẠI ở bước UI_SET_ABSBIT");
            return env->NewStringUTF(log.c_str());
        }
    }
    appendLog(log, "UI_SET_ABSBIT (8 trục): OK");

    // Cấu hình dải giá trị từng trục bằng UI_ABS_SETUP (API hiện đại, kernel >= 4.5).
    struct AbsRange { int code; int min; int max; };
    const AbsRange ranges[] = {
        {ABS_X, -32767, 32767}, {ABS_Y, -32767, 32767},
        {ABS_Z, -32767, 32767}, {ABS_RZ, -32767, 32767},
        {ABS_BRAKE, 0, 1023},   {ABS_GAS, 0, 1023},
        {ABS_HAT0X, -1, 1},     {ABS_HAT0Y, -1, 1},
    };
    for (const AbsRange& r : ranges) {
        uinput_abs_setup setup{};
        setup.code = (uint16_t)r.code;
        setup.absinfo.minimum = r.min;
        setup.absinfo.maximum = r.max;
        setup.absinfo.fuzz = 0;
        setup.absinfo.flat = 0;
        setup.absinfo.value = 0;
        if (ioctl(fd, UI_ABS_SETUP, &setup) < 0) {
            appendLog(log, "LỖI UI_ABS_SETUP mã " + std::to_string(r.code) + ": errno=" +
                             std::to_string(errno) + " (" + strerror(errno) + ")");
            close(fd);
            appendLog(log, "KẾT QUẢ: THẤT BẠI ở bước UI_ABS_SETUP");
            return env->NewStringUTF(log.c_str());
        }
    }
    appendLog(log, "UI_ABS_SETUP (dải trục): OK");

    // Thông tin thiết bị bằng UI_DEV_SETUP.
    uinput_setup devSetup{};
    devSetup.id.bustype = BUS_BLUETOOTH;  // 0x05 — giả lập tay cầm Bluetooth
    devSetup.id.vendor = (uint16_t)kVendor;
    devSetup.id.product = (uint16_t)kProduct;
    devSetup.id.version = 1;
    strncpy(devSetup.name, kName, sizeof(devSetup.name) - 1);
    devSetup.ff_effects_max = 0;
    if (ioctl(fd, UI_DEV_SETUP, &devSetup) < 0) {
        appendLog(log, "LỖI UI_DEV_SETUP: errno=" + std::to_string(errno) + " (" + strerror(errno) + ")");
        close(fd);
        appendLog(log, "KẾT QUẢ: THẤT BẠI ở bước UI_DEV_SETUP");
        return env->NewStringUTF(log.c_str());
    }
    appendLog(log, "UI_DEV_SETUP (tên/VID/PID): OK");

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        appendLog(log, "LỖI UI_DEV_CREATE: errno=" + std::to_string(errno) + " (" + strerror(errno) + ")");
        close(fd);
        appendLog(log, "KẾT QUẢ: THẤT BẠI ở bước UI_DEV_CREATE");
        return env->NewStringUTF(log.c_str());
    }
    appendLog(log, "UI_DEV_CREATE: OK — thiết bị đã đăng ký với kernel");

    g_fd = fd;
    g_lastButtons = 0;
    g_lastHat = 0;
    for (int i = 0; i < 6; i++) g_lastAxes[i] = 0;
    appendLog(log, "OK");
    return env->NewStringUTF(log.c_str());
}

// Gửi một gói trạng thái 15 byte (định dạng của ReportEncoder.encodeStatePacket).
// Trả về true nếu ghi thành công.
JNIEXPORT jboolean JNICALL
Java_com_nguyen_flexpad_backend_UinputNative_nativeSendState(JNIEnv* env, jclass /* clazz */, jbyteArray packet) {
    if (g_fd < 0 || packet == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(packet);
    if (len < 15) return JNI_FALSE;
    jbyte raw[15];
    env->GetByteArrayRegion(packet, 0, 15, raw);
    auto u8 = [&](int i) -> int { return (uint8_t)raw[i]; };
    auto i16 = [&](int i) -> int { return (int16_t)((uint8_t)raw[i] | ((uint8_t)raw[i + 1] << 8)); };
    auto u16 = [&](int i) -> int { return (uint16_t)((uint8_t)raw[i] | ((uint8_t)raw[i + 1] << 8)); };

    uint16_t buttons = (uint16_t)u16(0);
    int hat = u8(2);
    int axes[6] = {i16(3), i16(5), i16(7), i16(9), u16(11), u16(13)};

    input_event buf[32];
    int n = 0;

    // Nút thay đổi.
    uint16_t changed = buttons ^ g_lastButtons;
    for (int bit = 0; bit < 15; bit++) {
        if (changed & (1 << bit)) {
            int code = buttonCodeForBit(bit);
            if (code >= 0) pushEvent(buf, n, EV_KEY, (uint16_t)code, (buttons & (1 << bit)) ? 1 : 0);
        }
    }
    // Trục thay đổi.
    const int axisCodes[6] = {ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_BRAKE, ABS_GAS};
    for (int i = 0; i < 6; i++) {
        if (axes[i] != g_lastAxes[i]) pushEvent(buf, n, EV_ABS, (uint16_t)axisCodes[i], axes[i]);
    }
    // Hat: đổi sang cặp HAT0X/HAT0Y.
    static const int hatX[9] = {0, 0, 1, 1, 1, 0, -1, -1, -1};
    static const int hatY[9] = {0, -1, -1, 0, 1, 1, 1, 0, -1};
    int hx = (hat >= 0 && hat <= 8) ? hatX[hat] : 0;
    int hy = (hat >= 0 && hat <= 8) ? hatY[hat] : 0;
    static int lastHx = 0, lastHy = 0;
    if (hx != lastHx) pushEvent(buf, n, EV_ABS, ABS_HAT0X, hx);
    if (hy != lastHy) pushEvent(buf, n, EV_ABS, ABS_HAT0Y, hy);

    if (n == 0) return JNI_TRUE;  // không có gì thay đổi
    pushEvent(buf, n, EV_SYN, SYN_REPORT, 0);
    std::string err;
    if (!flushEvents(buf, n, err)) return JNI_FALSE;

    g_lastButtons = buttons;
    g_lastHat = hat;
    for (int i = 0; i < 6; i++) g_lastAxes[i] = axes[i];
    lastHx = hx;
    lastHy = hy;
    return JNI_TRUE;
}

// Hủy thiết bị uinput.
JNIEXPORT jstring JNICALL
Java_com_nguyen_flexpad_backend_UinputNative_nativeDestroy(JNIEnv* env, jclass /* clazz */) {
    std::string log;
    if (g_fd < 0) {
        appendLog(log, "Chưa có thiết bị uinput nào để hủy.");
        return env->NewStringUTF(log.c_str());
    }
    if (ioctl(g_fd, UI_DEV_DESTROY) < 0) {
        appendLog(log, "LỖI UI_DEV_DESTROY: errno=" + std::to_string(errno) + " (" + strerror(errno) +
                         ") — vẫn đóng fd.");
    } else {
        appendLog(log, "UI_DEV_DESTROY: OK");
    }
    close(g_fd);
    g_fd = -1;
    appendLog(log, "OK");
    return env->NewStringUTF(log.c_str());
}

}  // extern "C"
