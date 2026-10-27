package com.nguyen.flexpad.backend

import com.nguyen.flexpad.gamepad.GamepadState
import com.nguyen.flexpad.gamepad.ReportEncoder

/**
 * Backend B2 — tạo tay cầm ảo qua /dev/uinput, phần ioctl nằm ở mã native
 * (app/src/main/cpp/uinput_jni.cpp) vì SDK không exposing ioctl uinput.
 * [nativeLibDir] do app truyền qua AIDL initPaths để nạp .so trong tiến trình service.
 */
class UinputBackend : InputBackend {

    override val id: BackendId = BackendId.UINPUT

    @Volatile private var created = false
    @Volatile var nativeLibDir: String? = null

    override fun create(): String {
        val sb = StringBuilder()
        if (!UinputNative.ensureLoaded(nativeLibDir)) {
            sb.appendLine("B2/UINPUT: KHÔNG nạp được libflexpad_uinput.so — ${UinputNative.loadError}")
            sb.appendLine("B2/UINPUT: dừng. (Kiểm tra APK có chứa lib/arm64-v8a/libflexpad_uinput.so không.)")
            return sb.toString()
        }
        val nativeLog = try {
            UinputNative.nativeCreate()
        } catch (e: Throwable) {
            sb.appendLine("B2/UINPUT: gọi JNI nativeCreate lỗi — ${e.javaClass.simpleName}: ${e.message}")
            return sb.toString()
        }
        sb.append(nativeLog)
        created = nativeLog.lines().any { it.startsWith("OK") }
        if (created) {
            sb.appendLine("B2/UINPUT: native báo tạo xong (chờ kiểm chứng dumpsys ở bước (d)).")
        }
        return sb.toString()
    }

    override fun sendState(state: GamepadState): Boolean {
        if (!created) return false
        return try {
            UinputNative.nativeSendState(ReportEncoder.encodeStatePacket(state))
        } catch (e: Throwable) {
            false
        }
    }

    override fun destroy(): String {
        val sb = StringBuilder()
        if (!UinputNative.isLoaded) {
            sb.appendLine("B2/UINPUT: thư viện native chưa được nạp, không có gì để hủy.")
            created = false
            return sb.toString()
        }
        try {
            sb.append(UinputNative.nativeDestroy())
        } catch (e: Throwable) {
            sb.appendLine("B2/UINPUT: gọi JNI nativeDestroy lỗi — ${e.javaClass.simpleName}: ${e.message}")
        }
        created = false
        return sb.toString()
    }

    override fun isCreated(): Boolean = created
}
