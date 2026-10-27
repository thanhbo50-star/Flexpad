package com.nguyen.flexpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nguyen.flexpad.PadController
import com.nguyen.flexpad.gamepad.HatValue
import com.nguyen.flexpad.gamepad.PadButton
import com.nguyen.flexpad.shizuku.ShizukuBridge
import kotlinx.coroutines.launch

/**
 * Panel kiểm thử (MỐC 2): cùng một PadScreen như khi chơi thật, kèm bảng đọc
 * trạng thái trực tiếp (nút đang giữ, hat, 6 trục dạng thanh) để đối chiếu với
 * trang kiểm tra gamepad trên trình duyệt (vd hardwaretester.com/gamepad) —
 * mỗi nút phải hiện đúng tên (A là A, X là X, LB là LB...), trục đúng chiều.
 */
@Composable
fun TesterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppSettings.flow(context).collectAsState(initial = PadSettings())
    val state by PadController.uiState.collectAsState()
    var deviceInfo by remember { mutableStateOf("(đang kiểm tra thiết bị…)") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        deviceInfo = ShizukuBridge.deviceStatus()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Kiểm thử tay cầm (MỐC 2)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Bấm/gạt thử mọi nút và cần ngay trên pad dưới đây. Nếu thiết bị ảo đã được tạo, " +
                "mở thêm trang hardwaretester.com/gamepad trong Chrome để đối chiếu tên nút và trục.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "LƯU Ý QUAN TRỌNG: pad trong tab này chỉ để kiểm tra cục bộ. Theo quy tắc của Android, " +
                "phím tay cầm luôn đi vào cửa sổ ĐANG FOCUS — khi FlexPad đang ở trước (kể cả chia đôi " +
                "màn hình với game) thì game sẽ KHÔNG nhận được phím. Chơi thật phải dùng pad overlay: " +
                "Bắt đầu phiên → mở game TOÀN MÀN HÌNH → gập Flex Mode.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { deviceInfo = ShizukuBridge.deviceStatus() }
            }) { Text("Kiểm tra thiết bị") }
            OutlinedButton(onClick = { PadController.resetToNeutral() }) {
                Text("Đặt lại trung tính")
            }
        }
        Text(deviceInfo, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(330.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            PadScreen(settings = settings, modifier = Modifier.fillMaxWidth().height(330.dp))
        }

        Spacer(Modifier.height(12.dp))
        val pressedNames = PadButton.entries
            .filter { state.buttonsMask and it.maskBit != 0 }
            .joinToString(", ") { it.label }
        Text(
            "Nút đang giữ: ${if (pressedNames.isEmpty()) "(không)" else pressedNames}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("D-pad (hat): ${hatLabel(state.hat)}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        AxisBar("Cần trái X", (state.leftX + 32767) / 65534f, state.leftX.toString())
        AxisBar("Cần trái Y", (state.leftY + 32767) / 65534f, state.leftY.toString())
        AxisBar("Cần phải X", (state.rightX + 32767) / 65534f, state.rightX.toString())
        AxisBar("Cần phải Y", (state.rightY + 32767) / 65534f, state.rightY.toString())
        AxisBar("Trigger trái (L2)", state.leftTrigger / 1023f, state.leftTrigger.toString())
        AxisBar("Trigger phải (R2)", state.rightTrigger / 1023f, state.rightTrigger.toString())
    }
}

private fun hatLabel(hat: Int): String = when (hat) {
    HatValue.NEUTRAL -> "trung tính"
    HatValue.UP -> "LÊN"
    HatValue.UP_RIGHT -> "LÊN-PHẢI"
    HatValue.RIGHT -> "PHẢI"
    HatValue.DOWN_RIGHT -> "XUỐNG-PHẢI"
    HatValue.DOWN -> "XUỐNG"
    HatValue.DOWN_LEFT -> "XUỐNG-TRÁI"
    HatValue.LEFT -> "TRÁI"
    HatValue.UP_LEFT -> "LÊN-TRÁI"
    else -> "lạ ($hat)"
}

@Composable
private fun AxisBar(label: String, fraction: Float, valueText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.weight(0.32f), style = MaterialTheme.typography.bodySmall)
        Box(
            modifier = Modifier
                .weight(0.5f)
                .height(12.dp)
                .background(Color(0xFFE0E0E0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(valueText, modifier = Modifier.weight(0.18f), style = MaterialTheme.typography.bodySmall)
    }
}
