package com.nguyen.flexpad.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.nguyen.flexpad.IFlexPadCallback
import com.nguyen.flexpad.IFlexPadService
import com.nguyen.flexpad.haptics.ButtonHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Cầu nối phía app tới Shizuku UserService.
 *
 * LƯU Ý QUAN TRỌNG (phụ lục, mục 7) — tham số daemon của UserServiceArgs:
 * ta đặt daemon(true). Nghĩa là tiến trình UserService KHÔNG bị Shizuku kill
 * khi tiến trình app chết, nên tay cầm ảo (và fd /dev/uhid) sống sót qua việc
 * app bị hệ thống thu hồi — đúng yêu cầu "thiết bị sống suốt phiên" của đặc tả.
 * Đánh đổi: muốn tắt hẳn phải bấm "Hủy thiết bị"/"Dừng tay cầm" trong app,
 * hoặc tắt Shizuku. Điều này được ghi rõ trong README.
 */
object ShizukuBridge {

    private const val REQUEST_CODE_PERMISSION = 20260

    private val _status = MutableStateFlow("Chưa khởi tạo Shizuku")
    val status: StateFlow<String> = _status

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected

    @Volatile
    private var service: IFlexPadService? = null

    private var appContext: Context? = null

    /** Callback nhận rumble từ UserService (UHID OUTPUT của game) -> rung thật trên máy. */
    private val rumbleCallback = object : IFlexPadCallback.Stub() {
        override fun onRumble(leftMagnitude: Int, rightMagnitude: Int) {
            ButtonHaptics.rumble(maxOf(leftMagnitude, rightMagnitude))
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _status.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
            "Đã được cấp quyền Shizuku — hãy bấm Kết nối UserService"
        } else {
            "Quyền Shizuku bị TỪ CHỐI — mở app Shizuku kiểm tra lại"
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _serviceConnected.value = false
        service = null
        _status.value = "Mất kết nối tới Shizuku (binder dead) — Shizuku có thể đã tắt"
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IFlexPadService.Stub.asInterface(binder)
            service = svc
            _serviceConnected.value = svc != null
            if (svc != null) {
                // Truyền đường dẫn thư viện native cho backend B2 (JNI trong tiến trình service).
                val dir = appContext?.applicationInfo?.nativeLibraryDir
                try {
                    svc.initPaths(dir)
                    svc.registerCallback(rumbleCallback)
                } catch (e: Exception) {
                    _status.value = "UserService đã nối nhưng initPaths/registerCallback lỗi: ${e.message}"
                    return
                }
                _status.value = "Đã kết nối UserService (ping: ${runCatching { svc.ping() }.getOrElse { "lỗi ${it.message}" }})"
            } else {
                _status.value = "Kết nối UserService thất bại: binder rỗng"
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _serviceConnected.value = false
            _status.value = "UserService đã ngắt kết nối"
        }
    }

    /** Gọi một lần từ MainActivity.onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshStatus()
    }

    fun refreshStatus() {
        _status.value = when {
            !Shizuku.pingBinder() ->
                "Không thấy tiến trình Shizuku — hãy mở app Shizuku và khởi động (ADB không dây hoặc root)"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                "Shizuku sẵn sàng (uid server=${Shizuku.getUid()}, phiên bản=${Shizuku.getVersion()})"
            else ->
                "Shizuku đang chạy nhưng FlexPad CHƯA được cấp quyền — bấm Xin quyền Shizuku"
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            _status.value = "Chưa thấy Shizuku — khởi động Shizuku trước"
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            _status.value = "Đã có quyền Shizuku rồi"
            return
        }
        Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        _status.value = "Đã gửi yêu cầu quyền tới Shizuku — chờ anh xác nhận trong hộp thoại của Shizuku"
    }

    /** Thông tin Shizuku phía app — nối vào cuối báo cáo chẩn đoán khi hiển thị. */
    fun shizukuInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("Phía app: pingBinder=${Shizuku.pingBinder()}")
        if (Shizuku.pingBinder()) {
            sb.appendLine("Shizuku.getVersion()=${Shizuku.getVersion()}")
            sb.appendLine("Shizuku.getUid()=${Shizuku.getUid()} (0 = root, 2000 = shell/ADB)")
            sb.appendLine(
                "Chế độ Shizuku: " + if (Shizuku.getUid() == 0) "ROOT" else "ADB/shell"
            )
            sb.appendLine("Quyền của FlexPad: ${
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "ĐÃ CẤP" else "CHƯA CẤP"
            }")
        }
        return sb.toString().trimEnd()
    }

    /** Gắn (bind) UserService. daemon(true) — xem ghi chú ở đầu file. */
    fun bindService() {
        val ctx = appContext ?: run {
            _status.value = "Chưa init ShizukuBridge"
            return
        }
        if (!Shizuku.pingBinder()) {
            _status.value = "Chưa thấy Shizuku — khởi động Shizuku trước"
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _status.value = "Chưa có quyền Shizuku — bấm Xin quyền Shizuku trước"
            return
        }
        val versionCode = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (e: Exception) {
            1
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, FlexPadUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("pad")
            .debuggable(false)
            .version(versionCode)
            .tag("flexpad-pad")
        try {
            Shizuku.bindUserService(args, connection)
            _status.value = "Đang kết nối UserService..."
        } catch (e: Exception) {
            _status.value = "bindUserService lỗi: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun unbindService() {
        // Hủy đăng ký callback rumble trước khi ngắt (binder chết cũng tự xóa phía service).
        try {
            service?.unregisterCallback()
        } catch (e: Exception) {
            // Bỏ qua: việc ngắt kết nối vẫn tiếp tục, callback phía service sẽ mất hiệu lực theo binder.
        }
        val ctx = appContext ?: return
        val versionCode = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (e: Exception) {
            1
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, FlexPadUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("pad")
            .debuggable(false)
            .version(versionCode)
            .tag("flexpad-pad")
        try {
            Shizuku.unbindUserService(args, connection, true)
        } catch (e: Exception) {
            _status.value = "unbindUserService lỗi: ${e.javaClass.simpleName}: ${e.message}"
        }
        service = null
        _serviceConnected.value = false
    }

    private fun requireService(): IFlexPadService =
        service ?: throw IllegalStateException(
            "Chưa kết nối UserService — hãy cấp quyền Shizuku rồi bấm 'Kết nối UserService'"
        )

    // ---- Các lời gọi chặn (blocking) — luôn chạy trên Dispatchers.IO ----

    suspend fun runDiagnostics(): String = withContext(Dispatchers.IO) {
        try {
            requireService().runDiagnostics()
        } catch (e: Exception) {
            "Không chạy được chẩn đoán: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun createDevice(backend: Int): String = withContext(Dispatchers.IO) {
        try {
            requireService().createDevice(backend)
        } catch (e: Exception) {
            "Không tạo được thiết bị: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun destroyDevice(): String = withContext(Dispatchers.IO) {
        try {
            requireService().destroyDevice()
        } catch (e: Exception) {
            "Không hủy được thiết bị: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun testInjectButtonA(): String = withContext(Dispatchers.IO) {
        try {
            requireService().testInjectButtonA()
        } catch (e: Exception) {
            "Không thử được B4: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun eventLog(): String = withContext(Dispatchers.IO) {
        try {
            requireService().eventLog()
        } catch (e: Exception) {
            "Không đọc được nhật ký sự kiện: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Cửa sổ đang focus theo dumpsys window (kiểm chứng overlay không giành focus). */
    suspend fun windowFocus(): String = withContext(Dispatchers.IO) {
        try {
            requireService().windowFocus()
        } catch (e: Exception) {
            "Không kiểm tra được focus: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Bật hỗ trợ freeform qua service (settings global). */
    suspend fun applyFreeformSettings(): String = withContext(Dispatchers.IO) {
        try {
            requireService().applyFreeformSettings()
        } catch (e: Exception) {
            "Không áp dụng được freeform: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Trạng thái thiết bị ảo hiện tại (đã tạo chưa, backend nào). */
    suspend fun deviceStatus(): String = withContext(Dispatchers.IO) {
        try {
            val svc = requireService()
            if (svc.isDeviceCreated) {
                "Thiết bị ảo ĐANG SỐNG — backend: ${svc.activeBackend()}"
            } else {
                "Chưa có thiết bị ảo nào (hãy Tạo thiết bị ở tab Chẩn đoán trước)"
            }
        } catch (e: Exception) {
            "Không kiểm tra được thiết bị: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Gửi trạng thái — đường nóng, gọi trực tiếp từ PadController (binder oneway, không chặn lâu). */
    fun sendState(packed: ByteArray) {
        val svc = service ?: return
        try {
            svc.sendState(packed)
        } catch (e: Exception) {
            // Đường nóng: không ném ra giao diện; trạng thái kết nối sẽ phản ánh qua status.
        }
    }
}
