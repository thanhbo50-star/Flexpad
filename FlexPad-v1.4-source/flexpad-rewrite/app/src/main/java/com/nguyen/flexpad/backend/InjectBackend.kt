package com.nguyen.flexpad.backend

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Backend B4 — tiêm sự kiện qua IInputManager.injectInputEvent (hidden API),
 * truy cập bằng ShizukuBinderWrapper để lời gọi mang danh tính shell
 * (shell có quyền INJECT_EVENTS).
 *
 * GIỚI HẠN ĐÃ BIẾT (ghi rõ theo đặc tả): cách này KHÔNG tạo thiết bị thật.
 * KeyEvent/MotionEvent được tiêm với deviceId = VIRTUAL_KEYBOARD (-1) hoặc một
 * thiết bị ảo hệ thống; nhiều game (kể cả Genshin/ZZZ) kiểm tra nguồn thiết bị
 * và có thể bỏ qua. B4 chỉ để kiểm chứng đường tiêm sự kiện còn sống, và là
 * phương án cuối khi cả B1 lẫn B2 đều bị kernel/SELinux chặn.
 */
object InjectBackend {

    /**
     * Lấy IInputManager đã bọc ShizukuBinderWrapper bằng phản chiếu toàn bộ,
     * vì IInputManager và ServiceManager đều là API ẩn (không có trong SDK stub).
     * Trả về Pair(proxy, null) khi thành công hoặc Pair(null, mô tả lỗi).
     */
    private fun inputManagerProxy(): Pair<Any?, String?> {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder
                ?: return Pair(null, "ServiceManager.getService(\"input\") trả về null")
            val wrapped = ShizukuBinderWrapper(binder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterface.invoke(null, wrapped)
                ?: return Pair(null, "IInputManager.Stub.asInterface trả về null")
            Pair(proxy, null)
        } catch (e: Exception) {
            Pair(null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Tiêm một KeyEvent; trả về nhật ký kèm giá trị boolean của injectInputEvent. */
    fun injectKey(keyCode: Int, action: Int): String {
        val (proxy, err) = inputManagerProxy()
        if (proxy == null) return "B4/INJECT: không lấy được IInputManager — $err"
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, action, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_GAMEPAD
        )
        return try {
            val method = proxy.javaClass.getMethod(
                "injectInputEvent",
                Class.forName("android.view.InputEvent"),
                Int::class.javaPrimitiveType
            )
            // 0 = INJECT_INPUT_EVENT_MODE_ASYNC: tiêm ngay, không chờ xử lý xong.
            val result = method.invoke(proxy, event, 0) as? Boolean
            "B4/INJECT: injectInputEvent(KeyEvent code=$keyCode action=$action source=SOURCE_GAMEPAD) -> $result"
        } catch (e: Exception) {
            "B4/INJECT: gọi injectInputEvent lỗi — ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Thử gửi trọn một lần bấm nút A (DOWN rồi UP) để người dùng kiểm chứng. */
    fun testPressButtonA(): String {
        val sb = StringBuilder()
        sb.appendLine("B4/INJECT: bắt đầu thử gửi nút A (KEYCODE_BUTTON_A = ${KeyEvent.KEYCODE_BUTTON_A}).")
        sb.appendLine(injectKey(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN))
        Thread.sleep(70)
        sb.appendLine(injectKey(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP))
        sb.appendLine(
            "B4/INJECT: xong. LƯU Ý: sự kiện mang deviceId ảo (-1); hãy kiểm chứng ở app/game " +
                "đang mở — nếu game không phản ứng thì đó là giới hạn đã biết của B4, không phải lỗi lạ."
        )
        return sb.toString()
    }
}
