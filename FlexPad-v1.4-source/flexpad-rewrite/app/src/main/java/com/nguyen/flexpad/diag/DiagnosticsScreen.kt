package com.nguyen.flexpad.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.nguyen.flexpad.overlay.PadSessionState
import com.nguyen.flexpad.shizuku.ShizukuBridge
import kotlinx.coroutines.launch

/**
 * Màn hình "Chẩn đoán" (MỐC 1):
 *  - Chạy báo cáo chẩn đoán đầy đủ (mục 3.1) và sao chép được.
 *  - Tạo thiết bị với backend tự chọn (Tự động B1->B2, chỉ B1, chỉ B2),
 *    hiển thị nhật ký từng bước kèm kiểm chứng dumpsys input.
 *  - Thử backend B4 (tiêm nút A) và xem nhật ký sự kiện kernel của UHID.
 */
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var backendChoice by remember { mutableIntStateOf(0) } // 0=tự động, 1=B1, 2=B2
    var busy by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf("(chưa chạy chẩn đoán)") }
    var actionLog by remember { mutableStateOf("(chưa có thao tác)") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Chẩn đoán & tạo thiết bị (MỐC 1)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Nguyên tắc: chỉ coi là tạo thành công khi dumpsys input thấy " +
                "\"Xbox Wireless Controller\". Mọi lỗi đều hiện errno thật.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val svcReport = ShizukuBridge.runDiagnostics()
                        reportText = svcReport + "\n\n[8] Thông tin Shizuku phía app\n" +
                            ShizukuBridge.shizukuInfo()
                        busy = false
                    }
                }
            ) { Text("Chạy chẩn đoán") }
            OutlinedButton(
                enabled = !busy,
                onClick = { clipboard.setText(AnnotatedString(reportText)) }
            ) { Text("Sao chép báo cáo") }
        }

        Spacer(Modifier.height(12.dp))
        Text("Backend tạo thiết bị:", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = backendChoice == 0,
                onClick = { backendChoice = 0 },
                label = { Text("Tự động B1→B2", maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = backendChoice == 1,
                onClick = { backendChoice = 1 },
                label = { Text("B1 UHID", maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = backendChoice == 2,
                onClick = { backendChoice = 2 },
                label = { Text("B2 uinput", maxLines = 1, softWrap = false) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    actionLog = "Đang tạo thiết bị..."
                    scope.launch {
                        actionLog = ShizukuBridge.createDevice(backendChoice)
                        busy = false
                    }
                }
            ) { Text("Tạo thiết bị") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        actionLog = ShizukuBridge.destroyDevice()
                        busy = false
                    }
                }
            ) { Text("Hủy thiết bị") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        actionLog = ShizukuBridge.testInjectButtonA()
                        busy = false
                    }
                }
            ) { Text("Thử gửi nút A (B4)") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        actionLog = ShizukuBridge.eventLog()
                        busy = false
                    }
                }
            ) { Text("Nhật ký sự kiện kernel") }
        }
        OutlinedButton(
            enabled = !busy,
            onClick = { clipboard.setText(AnnotatedString(actionLog)) }
        ) { Text("Sao chép nhật ký thao tác") }

        Spacer(Modifier.height(12.dp))
        Text("Nhật ký thao tác:", style = MaterialTheme.typography.labelLarge)
        LogBox(text = actionLog)
        Spacer(Modifier.height(12.dp))
        Text("Báo cáo chẩn đoán:", style = MaterialTheme.typography.labelLarge)
        LogBox(text = reportText)

        Spacer(Modifier.height(16.dp))
        Text("Bản lề & cảm biến (v1.4)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Quét phía app: máy có cảm biến góc bản lề không, tracker thấy DisplayFeature gì, " +
                "trạng thái gập phía app và phía service khác nhau ra sao. Gập máy nửa chừng " +
                "rồi bấm quét để bắt trạng thái Flex Mode thật.",
            style = MaterialTheme.typography.bodySmall
        )
        var foldScan by remember { mutableStateOf("(chưa quét)") }
        val scanContext = androidx.compose.ui.platform.LocalContext.current
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = { foldScan = scanFoldHardware(scanContext) }
        ) { Text("Quét bản lề & cảm biến") }
        Spacer(Modifier.height(6.dp))
        LogBox(text = foldScan)

        Spacer(Modifier.height(16.dp))
        Text("Kiểm chứng focus (MỐC 3)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Overlay pad được thiết kế KHÔNG giành focus. Cách kiểm chứng thật: mở game, " +
                "gập Flex Mode cho pad hiện, chạm vài nút trên pad — FlexPad tự chụp lại " +
                "cửa sổ đang focus ngay lần chạm đầu tiên. Quay lại đây xem kết quả: " +
                "mCurrentFocus phải là game, không phải FlexPad.",
            style = MaterialTheme.typography.bodySmall
        )
        val lastFocus by PadSessionState.lastFocusCheck.collectAsState()
        Spacer(Modifier.height(6.dp))
        LogBox(text = lastFocus)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    actionLog = "Focus hiện tại:\n" + ShizukuBridge.windowFocus()
                    busy = false
                }
            }
        ) { Text("Chụp focus ngay bây giờ") }
    }
}

/** Quét phần cứng gập phía app: cảm biến góc bản lề + trạng thái tracker của app và service. */
private fun scanFoldHardware(context: android.content.Context): String {
    val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        ?: return "Không lấy được SensorManager."
    val sb = StringBuilder()
    val hinge = sm.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE)
    sb.appendLine(
        "Cảm biến mặc định TYPE_HINGE_ANGLE (36): " +
            (hinge?.let { "${it.name} — ${it.vendor}" } ?: "KHÔNG CÓ")
    )
    val all = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
    sb.appendLine("Tổng số cảm biến: ${all.size}")
    val keys = listOf("hinge", "fold", "angle", "posture")
    val candidates = all.filter { s ->
        s.type == android.hardware.Sensor.TYPE_HINGE_ANGLE ||
            keys.any { k -> s.name.lowercase().contains(k) }
    }
    if (candidates.isEmpty()) {
        sb.appendLine("Cảm biến liên quan gập/bản lề: (không tìm thấy)")
    } else {
        candidates.forEach { s -> sb.appendLine("• ${s.name} | type=${s.type} | ${s.vendor}") }
    }
    val a = com.nguyen.flexpad.fold.FoldStateStore.activityState.value
    val sv = com.nguyen.flexpad.fold.FoldStateStore.serviceState.value
    sb.appendLine()
    sb.appendLine("[Phía app/Activity] ${a.describe()}")
    sb.appendLine("  features: ${a.featuresRaw}")
    sb.appendLine("[Phía service] ${sv.describe()}")
    sb.appendLine("  features: ${sv.featuresRaw}")
    return sb.toString()
}

/** Hộp hiển thị nhật ký chữ đơn cách (monospace), cuộn được, nền tối chữ sáng. */
@Composable
private fun LogBox(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 380.dp)
            .background(Color(0xFF101418))
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(
            text = text,
            color = Color(0xFFCFE3D2),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}
