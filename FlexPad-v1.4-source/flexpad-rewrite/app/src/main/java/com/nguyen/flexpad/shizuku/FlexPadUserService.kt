package com.nguyen.flexpad.shizuku

import android.content.Context
import com.nguyen.flexpad.IFlexPadService
import com.nguyen.flexpad.backend.InputBackend
import com.nguyen.flexpad.backend.InjectBackend
import com.nguyen.flexpad.backend.UhidBackend
import com.nguyen.flexpad.backend.UinputBackend
import com.nguyen.flexpad.diag.Diagnostics
import com.nguyen.flexpad.gamepad.ReportEncoder
import kotlin.system.exitProcess

/**
 * Tiến trình Shizuku UserService của FlexPad (chạy dưới uid shell 2000 hoặc root 0).
 * Đây là nơi DUY NHẤT giữ file descriptor của /dev/uhid, /dev/uinput và tạo thiết bị.
 *
 * Quy tắc: KHÔNG bao giờ báo "thành công" nếu chưa kiểm chứng bằng dumpsys input
 * (bước (d) trong phụ lục). createDevice trả về nhật ký đầy đủ từng bước.
 */
class FlexPadUserService : IFlexPadService.Stub {

    constructor() : super()

    // Shizuku ưu tiên constructor có Context khi khởi tạo UserService.
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    // Nhật ký sự kiện kernel (UHID START/OPEN/GET_REPORT...) — tối đa 200 dòng gần nhất.
    private val eventLogLines = mutableListOf<String>()
    private val eventLogLock = Any()

    private fun logEvent(message: String) {
        synchronized(eventLogLock) {
            val ts = android.os.SystemClock.uptimeMillis()
            eventLogLines.add("[$ts] $message")
            while (eventLogLines.size > 200) eventLogLines.removeAt(0)
        }
    }

    // Callback về app (rumble) — do app đăng ký sau khi kết nối.
    @Volatile
    private var appCallback: com.nguyen.flexpad.IFlexPadCallback? = null

    private val uhidBackend = UhidBackend(
        logEvent = { message -> logEvent(message) },
        onRumble = { strong, weak ->
            val cb = appCallback
            if (cb != null) {
                try {
                    cb.onRumble(strong, weak)
                } catch (e: Exception) {
                    logEvent("Gửi rumble về app lỗi (callback chết?): ${e.javaClass.simpleName}: ${e.message}")
                    appCallback = null
                }
            }
        }
    )
    private val uinputBackend = UinputBackend()

    @Volatile
    private var activeBackend: InputBackend? = null

    override fun destroy() {
        destroyDeviceInternal()
    }

    override fun exit() {
        destroy()
        exitProcess(0)
    }

    override fun ping(): String = "flexpad-pad-ok"

    override fun initPaths(nativeLibDir: String?) {
        uinputBackend.nativeLibDir = nativeLibDir
        logEvent("initPaths: nativeLibDir=$nativeLibDir")
    }

    override fun runDiagnostics(): String = Diagnostics.collect()

    override fun createDevice(backend: Int): String {
        val sb = StringBuilder()
        activeBackend?.let {
            sb.appendLine("Thiết bị đã đang hoạt động bằng ${it.id.label}. Muốn tạo lại, hãy Hủy thiết bị trước.")
            return sb.toString()
        }
        when (backend) {
            1 -> tryCreate(uhidBackend, sb)
            2 -> tryCreate(uinputBackend, sb)
            else -> {
                // Tự động: B1 trước, thất bại (kể cả kiểm chứng dumpsys) thì phá bỏ và thử B2.
                if (!tryCreate(uhidBackend, sb)) {
                    sb.appendLine("--- Tự động chuyển sang backend dự phòng B2 ---")
                    tryCreate(uinputBackend, sb)
                }
            }
        }
        sb.appendLine(
            if (activeBackend != null) {
                "KẾT QUẢ CUỐI: THÀNH CÔNG bằng ${activeBackend!!.id.label} (đã kiểm chứng bằng dumpsys input)."
            } else {
                "KẾT QUẢ CUỐI: THẤT BẠI ở mọi backend đã thử. Hãy gửi lại toàn bộ nhật ký này " +
                    "kèm báo cáo Chẩn đoán (đặc biệt là errno và dòng avc denied)."
            }
        )
        return sb.toString()
    }

    /**
     * Tạo thiết bị bằng một backend rồi kiểm chứng bước (d).
     * Trả về true chỉ khi dumpsys input thấy "Xbox Wireless Controller".
     */
    private fun tryCreate(backend: InputBackend, sb: StringBuilder): Boolean {
        sb.appendLine("===== Thử backend ${backend.id.label} =====")
        val createLog = try {
            backend.create()
        } catch (e: Exception) {
            sb.appendLine("${backend.id.label}: create() ném ngoại lệ — ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        sb.append(createLog)
        if (!backend.isCreated()) {
            sb.appendLine("${backend.id.label}: backend báo chưa tạo được thiết bị — bỏ qua kiểm chứng.")
            return false
        }
        // Bước (d): kiểm chứng bằng dumpsys input, thử 2 lần vì InputReader cần chút thời gian.
        Thread.sleep(500)
        var verify = Diagnostics.verifyGamepadInDumpsys()
        if (verify.startsWith("KHÔNG THẤY")) {
            Thread.sleep(800)
            verify = Diagnostics.verifyGamepadInDumpsys()
        }
        sb.appendLine("Bước (d) KIỂM CHỨNG dumpsys input: $verify")
        return if (verify.startsWith("THẤY")) {
            activeBackend = backend
            true
        } else {
            sb.appendLine("${backend.id.label}: tạo xong nhưng KHÔNG qua được kiểm chứng — hủy để tránh trạng thái treo.")
            try {
                sb.append(backend.destroy())
            } catch (e: Exception) {
                sb.appendLine("Hủy sau kiểm chứng lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
            false
        }
    }

    private fun destroyDeviceInternal(): String {
        val sb = StringBuilder()
        val active = activeBackend
        if (active == null) {
            sb.appendLine("Chưa có thiết bị nào đang hoạt động.")
            return sb.toString()
        }
        try {
            sb.append(active.destroy())
        } catch (e: Exception) {
            sb.appendLine("Hủy thiết bị lỗi: ${e.javaClass.simpleName}: ${e.message}")
        }
        activeBackend = null
        sb.appendLine("Đã hủy thiết bị (${active.id.label}).")
        return sb.toString()
    }

    override fun destroyDevice(): String = destroyDeviceInternal()

    override fun isDeviceCreated(): Boolean = activeBackend != null

    override fun activeBackend(): String = activeBackend?.id?.name ?: ""

    override fun sendState(packedState: ByteArray?) {
        val backend = activeBackend ?: return
        if (packedState == null) return
        try {
            val state = ReportEncoder.decodeStatePacket(packedState)
            backend.sendState(state)
        } catch (e: Exception) {
            logEvent("sendState lỗi: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun testInjectButtonA(): String = InjectBackend.testPressButtonA()

    override fun eventLog(): String {
        synchronized(eventLogLock) {
            if (eventLogLines.isEmpty()) return "(chưa có sự kiện nào — hãy tạo thiết bị trước)"
            return eventLogLines.joinToString("\n")
        }
    }

    override fun windowFocus(): String {
        val r = com.nguyen.flexpad.diag.DiagTools.exec(listOf("dumpsys", "window"), 10000)
        if (r.exitCode == -999 || r.exitCode == -998) {
            return "Không chạy được dumpsys window: ${r.output}"
        }
        val focusLines = r.output.lines()
            .map { it.trim() }
            .filter { it.startsWith("mCurrentFocus") || it.startsWith("mFocusedApp") }
        if (focusLines.isEmpty()) {
            return "dumpsys window chạy được nhưng không thấy mCurrentFocus/mFocusedApp."
        }
        return focusLines.joinToString("\n")
    }

    override fun registerCallback(callback: com.nguyen.flexpad.IFlexPadCallback?) {
        appCallback = callback
        logEvent("App đã đăng ký callback rumble.")
    }

    override fun unregisterCallback() {
        appCallback = null
    }

    override fun applyFreeformSettings(): String {
        val sb = StringBuilder()
        val commands = listOf(
            listOf("settings", "put", "global", "enable_freeform_support", "1"),
            listOf("settings", "put", "global", "force_resizable_activities", "1")
        )
        for (cmd in commands) {
            val r = com.nguyen.flexpad.diag.DiagTools.exec(cmd, 3000)
            sb.appendLine("${cmd.joinToString(" ")} -> exit=${r.exitCode} ${r.output.trim()}")
        }
        sb.appendLine(
            "Đã áp dụng. Lưu ý: một số máy cần khởi động lại mới nhận freeform; " +
                "và game có chấp nhận resize hay không là do từng game (xem hướng dẫn trong Cài đặt)."
        )
        return sb.toString()
    }
}
