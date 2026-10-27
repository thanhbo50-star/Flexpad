package com.nguyen.flexpad.fold

import android.app.Activity
import android.app.Service
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * Quan sát tư thế gập — VIẾT LẠI ở v1.4 sau khi bản rewrite bị "mù tư thế".
 *
 * Bài học từ máy thật (Find N5, v1.3): luồng cũ đọc WindowInfoTracker bằng
 * APPLICATION context và gộp (combine) với luồng cảm biến không phát giá trị
 * khởi tạo -> luồng tổng KHÔNG BAO GIỜ phát dữ liệu, giao diện kẹt ở trạng
 * thái mặc định UNKNOWN/0x0, overlay không bao giờ hiện (kể cả "Luôn hiện
 * pad" vì computeRegion cần chiều cao màn hình > 0). Trong khi đó v0.1 —
 * dùng tracker theo ACTIVITY — nhận diện FLEX/TABLETOP tốt trên chính máy này.
 *
 * Thiết kế mới:
 *  - Phía app: [observeActivity] — đúng đường đi của v0.1.
 *  - Phía service: [observeService] — tạo WindowContext (API 31+) gắn với
 *    cửa sổ overlay để có context "có cửa sổ" hợp lệ cho tracker và metrics.
 *  - Mọi nguồn đều phát giá trị khởi tạo (cảm biến phát null trước) nên
 *    combine không bao giờ bị đói dữ liệu.
 *  - Lỗi của tracker được GHI VÀO FlexState.error và hiện lên UI/chẩn đoán,
 *    không nuốt lỗi nữa; tracker tự thử lại 3 lần trước khi bỏ cuộc.
 *  - Kích thước màn hình lấy độc lập với tracker (WindowManager của context
 *    có cửa sổ, dự phòng bằng DisplayManager) nên luôn > 0 khi có màn hình.
 *  - Nếu tracker không thấy FoldingFeature nhưng góc bản lề ở khoảng nửa mở
 *    (30..150 độ) thì suy ra TABLETOP — Flex Mode vẫn dùng được khi tracker mù.
 */
object FoldObserver {

    /** Đường đi đã kiểm chứng ở v0.1: tracker theo Activity. */
    fun observeActivity(activity: Activity): Flow<FlexState> =
        buildFlow(activity, activity.windowManager)

    /** Đường đi cho Service chạy nền: WindowContext theo loại cửa sổ overlay. */
    fun observeService(service: Service): Flow<FlexState> {
        val ctx: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dm = service.getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                runCatching {
                    service.createWindowContext(
                        display,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null
                    )
                }.getOrNull() ?: service
            } else {
                service
            }
        } else {
            service
        }
        val wm = ctx.getSystemService(WindowManager::class.java)
            ?: service.getSystemService(WindowManager::class.java)
        return buildFlow(ctx, wm)
    }

    private fun buildFlow(ctx: Context, wm: WindowManager?): Flow<FlexState> {
        val tracker = WindowInfoTracker.getOrCreate(ctx)
        val layoutFlow: Flow<Pair<WindowLayoutInfo?, String?>> =
            tracker.windowLayoutInfo(ctx)
                .map { info -> Pair<WindowLayoutInfo?, String?>(info, null) }
                .retryWhen { _, attempt ->
                    if (attempt < 3) {
                        delay(1200)
                        true
                    } else {
                        false
                    }
                }
                .catch { e ->
                    emit(Pair<WindowLayoutInfo?, String?>(null, "${e.javaClass.simpleName}: ${e.message}"))
                }
        return combine(layoutFlow, hingeAngleFlow(ctx)) { (info, layoutError), angle ->
            val (w, h) = metrics(ctx, wm)
            toState(info, angle, w, h, layoutError)
        }
    }

    /** Kích thước màn hình (px) — độc lập với tracker, có dự phòng DisplayManager. */
    private fun metrics(ctx: Context, wm: WindowManager?): Pair<Int, Int> {
        val fromWm = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
        if (fromWm != null && fromWm.width() > 0 && fromWm.height() > 0) {
            return fromWm.width() to fromWm.height()
        }
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val mode = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        if (mode != null && mode.physicalWidth > 0 && mode.physicalHeight > 0) {
            return mode.physicalWidth to mode.physicalHeight
        }
        val dmRes = ctx.resources.displayMetrics
        return dmRes.widthPixels to dmRes.heightPixels
    }

    private fun toState(
        info: WindowLayoutInfo?,
        angle: Float?,
        w: Int,
        h: Int,
        layoutError: String?
    ): FlexState {
        val features = info?.displayFeatures.orEmpty()
        val feature = features.filterIsInstance<FoldingFeature>().firstOrNull()
        val featuresRaw = when {
            info == null -> "(WindowLayoutInfo null — tracker lỗi: ${layoutError ?: "?"})"
            features.isEmpty() -> "(tracker trả về 0 DisplayFeature)"
            else -> features.joinToString("; ") { f ->
                if (f is FoldingFeature) {
                    "FoldingFeature[state=${f.state}, orientation=${f.orientation}, " +
                        "bounds=${f.bounds}, separating=${f.isSeparating}, occlusion=${f.occlusionType}]"
                } else {
                    f.javaClass.simpleName
                }
            }
        }
        var posture = when {
            feature == null -> FlexPosture.FLAT
            feature.state == FoldingFeature.State.HALF_OPENED &&
                feature.orientation == FoldingFeature.Orientation.HORIZONTAL -> FlexPosture.TABLETOP
            feature.state == FoldingFeature.State.HALF_OPENED &&
                feature.orientation == FoldingFeature.Orientation.VERTICAL -> FlexPosture.BOOK
            feature.state == FoldingFeature.State.FLAT -> FlexPosture.FLAT
            else -> FlexPosture.UNKNOWN
        }
        var raw = featuresRaw
        // Tracker mù (không thấy bản lề) nhưng cảm biến góc báo nửa mở -> vẫn vào Flex Mode.
        if (feature == null && angle != null && angle in 30f..150f) {
            posture = FlexPosture.TABLETOP
            raw += " [suy ra TABLETOP từ góc bản lề ${"%.0f".format(angle)}° vì tracker không thấy FoldingFeature]"
        }
        return FlexState(
            posture = posture,
            hingeBounds = feature?.bounds,
            hingeAngle = angle,
            displayWidthPx = w,
            displayHeightPx = h,
            error = layoutError,
            featuresRaw = raw
        )
    }

    /**
     * Luồng góc bản lề (độ). LUÔN phát null ngay khi bắt đầu (kể cả khi có cảm
     * biến) để combine không bị đói; sau đó phát giá trị thật khi cảm biến báo.
     * Máy không có cảm biến này thì chỉ phát null một lần rồi giữ luồng mở.
     */
    private fun hingeAngleFlow(context: Context): Flow<Float?> = callbackFlow {
        trySend(null)
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (sensorManager == null || sensor == null) {
            awaitClose { }
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val v = event?.values?.firstOrNull()
                if (v != null) trySend(v)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
