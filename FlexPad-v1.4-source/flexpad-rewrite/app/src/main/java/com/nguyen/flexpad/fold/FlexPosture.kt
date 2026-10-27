package com.nguyen.flexpad.fold

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow

/** Tư thế gập của máy. */
enum class FlexPosture {
    FLAT,       // Mở phẳng (hoặc máy không gập / không có FoldingFeature)
    TABLETOP,   // Nửa gập, bản lề nằm ngang — chế độ Flex Mode để chơi
    BOOK,       // Nửa gập, bản lề dọc
    CLOSED,     // Gập hẳn (màn hình trong tắt)
    UNKNOWN     // Chưa có dữ liệu
}

/**
 * Trạng thái gập tại một thời điểm.
 * [hingeBounds] là hình chữ nhật của bản lề theo toạ độ màn hình (px) —
 * vùng chơi game bắt đầu từ đáy của nó (hingeBounds.bottom) trở xuống đáy màn hình.
 * [error] ghi lại ngoại lệ của luồng WindowInfoTracker (nếu có) thay vì nuốt lỗi
 * như bản cũ — bản cũ vì nuốt lỗi mà kẹt vĩnh viễn ở trạng thái UNKNOWN mặc định.
 * [featuresRaw] là mô tả thô mọi DisplayFeature mà tracker trả về, phục vụ chẩn đoán.
 */
data class FlexState(
    val posture: FlexPosture = FlexPosture.UNKNOWN,
    val hingeBounds: Rect? = null,
    val hingeAngle: Float? = null,
    val displayWidthPx: Int = 0,
    val displayHeightPx: Int = 0,
    val error: String? = null,
    val featuresRaw: String = ""
) {
    fun describe(): String {
        val hinge = hingeBounds?.let {
            "bản lề: trái=${it.left} trên=${it.top} phải=${it.right} đáy=${it.bottom}"
        } ?: "bản lề: (không có)"
        val angle = hingeAngle?.let { "góc bản lề: ${"%.0f".format(it)}°" } ?: "góc bản lề: (không có giá trị)"
        val err = error?.let { "; lỗi tracker: $it" } ?: ""
        return "Tư thế: $posture; màn hình: ${displayWidthPx}x${displayHeightPx}px; $hinge; $angle$err"
    }
}

/** Nơi giữ trạng thái gập mới nhất của phía app (Activity) và phía service để tab Chẩn đoán đọc chéo. */
object FoldStateStore {
    val activityState = MutableStateFlow(FlexState())
    val serviceState = MutableStateFlow(FlexState())
}
