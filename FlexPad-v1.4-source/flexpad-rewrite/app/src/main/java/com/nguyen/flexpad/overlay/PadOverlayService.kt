package com.nguyen.flexpad.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nguyen.flexpad.MainActivity
import com.nguyen.flexpad.PadController
import com.nguyen.flexpad.fold.FlexPosture
import com.nguyen.flexpad.fold.FoldObserver
import com.nguyen.flexpad.fold.FoldStateStore
import com.nguyen.flexpad.haptics.ButtonHaptics
import com.nguyen.flexpad.shizuku.ShizukuBridge
import com.nguyen.flexpad.ui.AppSettings
import com.nguyen.flexpad.ui.PadScreen
import com.nguyen.flexpad.ui.PadSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground Service = chủ phiên tay cầm ảo (MỐC 3 + 4):
 *  - Giữ tiến trình ở mức foreground kèm thông báo "Dừng tay cầm".
 *  - Quan sát tư thế gập (FoldObserver) + cấu hình (AppSettings) để hiện/ẩn
 *    cửa sổ overlay tay cầm. Cửa sổ là TYPE_APPLICATION_OVERLAY với
 *    FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS —
 *    KHÔNG giành input focus, game vẫn nhận phím như thường (kiểm chứng bằng
 *    dumpsys window tại lần chạm pad đầu tiên, kết quả ở PadSessionState).
 *  - TABLETOP: overlay bám đúng vùng dưới bản lề theo hinge bounds thật.
 *    Tư thế khác: ẩn, trừ khi bật "Luôn hiện pad" (dải cố định ở đáy).
 *  - Thiết bị ảo KHÔNG bị hủy khi ẩn pad hay đổi tư thế — chỉ gửi trạng thái
 *    trung tính. Hủy thiết bị chỉ xảy ra khi dừng phiên.
 */
class PadOverlayService : Service() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var windowManager: WindowManager? = null
    private var host: OverlayComposeHost? = null
    private var currentParams: WindowManager.LayoutParams? = null

    @Volatile
    private var focusCheckedThisShow = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ButtonHaptics.init(this)
        ShizukuBridge.init(this)
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Không giấu lỗi: hiện thẳng lên tab Chính để biết phiên kẹt ở đâu.
            PadSessionState.sessionHeadline.value =
                "LỖI bắt đầu phiên: startForeground — ${e.javaClass.simpleName}: ${e.message}"
            PadSessionState.overlayMessage.value =
                "Dịch vụ nền không lên được: ${e.javaClass.simpleName}: ${e.message}"
        }
        PadSessionState.sessionHeadline.value = "Phiên đang chạy — đang theo dõi tư thế gập…"
        PadController.start()
        startObserving()
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        PadSessionState.sessionHeadline.value = "(phiên đã dừng)"
        PadController.stop()
        mainScope.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    // ---------- Quan sát tư thế + cấu hình ----------

    private var observing = false

    private fun startObserving() {
        if (observing) return
        observing = true
        mainScope.launch {
            combine(FoldObserver.observeService(this@PadOverlayService), AppSettings.flow(this@PadOverlayService)) { flex, settings ->
                flex to settings
            }.collect { (flex, settings) ->
                ButtonHaptics.enabled = settings.hapticsEnabled
                ButtonHaptics.intensity = settings.hapticIntensity
                FoldStateStore.serviceState.value = flex
                // Chiều cao hiệu dụng: ưu tiên số của tracker; tracker mù thì lấy
                // kích thước thật của màn hình đang bật — pad không được phép mất
                // chỉ vì tracker không trả dữ liệu (bài học v1.3).
                val effectiveHeight =
                    if (flex.displayHeightPx > 0) flex.displayHeightPx else realDisplayHeightPx()
                updateOverlay(flex.posture, flex.hingeBounds?.bottom, effectiveHeight, settings, flex.error)
            }
        }
    }

    /** Chiều cao thật (px) của màn hình mặc định — đường dự phòng khi tracker mù. */
    private fun realDisplayHeightPx(): Int {
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        val mode = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        if (mode != null && mode.physicalHeight > 0) return mode.physicalHeight
        return resources.displayMetrics.heightPixels
    }

    /** Tính vùng hiển thị pad (top + height theo px) hoặc null nếu phải ẩn. */
    private fun computeRegion(
        posture: FlexPosture,
        hingeBottom: Int?,
        displayHeightPx: Int,
        settings: PadSettings
    ): Pair<Int, Int>? {
        if (displayHeightPx <= 0) return null
        return when {
            posture == FlexPosture.TABLETOP && hingeBottom != null && hingeBottom in 1 until displayHeightPx -> {
                val rawHeight = displayHeightPx - hingeBottom
                val height = (rawHeight * settings.padScale).toInt().coerceIn(200, rawHeight)
                (displayHeightPx - height) to height
            }
            posture == FlexPosture.TABLETOP -> {
                // TABLETOP suy ra từ góc bản lề (tracker mù): không có đáy bản lề,
                // dùng tỉ lệ nửa dưới mặc định như chế độ luôn hiện.
                val height = (displayHeightPx * 0.46f * settings.padScale).toInt().coerceAtLeast(200)
                (displayHeightPx - height) to height
            }
            settings.alwaysShowPad -> {
                val height = (displayHeightPx * 0.46f * settings.padScale).toInt().coerceAtLeast(200)
                (displayHeightPx - height) to height
            }
            else -> null
        }
    }

    private fun updateOverlay(
        posture: FlexPosture,
        hingeBottom: Int?,
        displayHeightPx: Int,
        settings: PadSettings,
        trackerError: String? = null
    ) {
        val region = computeRegion(posture, hingeBottom, displayHeightPx, settings)
        if (region == null) {
            val errNote = trackerError?.let { " [lỗi đọc tư thế: $it]" } ?: ""
            PadSessionState.sessionHeadline.value =
                "Phiên đang chạy — tư thế $posture: pad đang ẨN " +
                    "(gập máy ~90° thành Flex Mode, hoặc bật 'Luôn hiện pad' trong Cài đặt)$errNote"
            hideOverlay()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            PadSessionState.overlayMessage.value =
                "Thiếu quyền 'Hiển thị trên các ứng dụng khác' — vào tab Cài đặt để cấp quyền cho FlexPad."
            hideOverlay()
            return
        }
        PadSessionState.overlayMessage.value = null
        val (top, height) = region
        val wm = windowManager ?: return
        val existing = host
        if (existing == null) {
            val newHost = OverlayComposeHost(this)
            newHost.composeView.setContent {
                MaterialTheme {
                    val liveSettings by AppSettings.flow(this@PadOverlayService)
                        .collectAsState(initial = PadSettings())
                    PadScreen(
                        settings = liveSettings,
                        onFirstTouch = { onPadFirstTouch() }
                    )
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = top
            }
            try {
                wm.addView(newHost.composeView, params)
            } catch (e: Exception) {
                PadSessionState.overlayMessage.value =
                    "Không gắn được cửa sổ overlay: ${e.javaClass.simpleName}: ${e.message}"
                newHost.destroy()
                return
            }
            host = newHost
            currentParams = params
            focusCheckedThisShow = false
            newHost.start()
            PadSessionState.overlayVisible.value = true
            PadSessionState.sessionHeadline.value =
                "Phiên đang chạy — pad ĐANG HIỆN ở nửa dưới (tư thế $posture)"
        } else {
            val params = currentParams ?: return
            if (params.y != top || params.height != height) {
                params.y = top
                params.height = height
                try {
                    wm.updateViewLayout(existing.composeView, params)
                } catch (e: Exception) {
                    PadSessionState.overlayMessage.value =
                        "Cập nhật vị trí overlay lỗi: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
    }

    private fun hideOverlay() {
        PadSessionState.overlayVisible.value = false
        val existing = host ?: return
        host = null
        currentParams = null
        try {
            windowManager?.removeViewImmediate(existing.composeView)
        } catch (e: Exception) {
            // Cửa sổ có thể đã bị hệ thống gỡ — bỏ qua, trạng thái vẫn nhất quán.
        }
        existing.destroy()
        PadController.resetToNeutral()
    }

    /** Lần chạm pad đầu tiên mỗi lần hiện: chụp lại cửa sổ đang focus để kiểm chứng. */
    private fun onPadFirstTouch() {
        if (focusCheckedThisShow) return
        focusCheckedThisShow = true
        ioScope.launch {
            PadSessionState.lastFocusCheck.value = ShizukuBridge.windowFocus()
        }
    }

    // ---------- Dừng phiên / thông báo ----------

    private fun stopSession() {
        ioScope.launch {
            try {
                ShizukuBridge.destroyDevice()
            } finally {
                PadController.resetToNeutral()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FlexPad — tay cầm ảo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo giữ phiên tay cầm ảo của FlexPad"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PadOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("FlexPad — tay cầm ảo đang bật")
            .setContentText("Thiết bị Xbox Wireless Controller ảo đang sống. Chạm để mở FlexPad.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Dừng tay cầm", stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "flexpad_pad_session"
        private const val NOTIFICATION_ID = 20261
        const val ACTION_STOP = "com.nguyen.flexpad.action.STOP_PAD"

        fun start(context: Context) {
            val intent = Intent(context, PadOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PadOverlayService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
