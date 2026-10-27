package com.nguyen.flexpad

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nguyen.flexpad.diag.DiagnosticsScreen
import com.nguyen.flexpad.fold.FoldObserver
import com.nguyen.flexpad.fold.FlexState
import com.nguyen.flexpad.fold.FoldStateStore
import kotlinx.coroutines.flow.onEach
import com.nguyen.flexpad.haptics.ButtonHaptics
import com.nguyen.flexpad.overlay.PadOverlayService
import com.nguyen.flexpad.overlay.PadSessionState
import com.nguyen.flexpad.shizuku.ShizukuBridge
import kotlinx.coroutines.launch
import com.nguyen.flexpad.ui.AppSettings
import com.nguyen.flexpad.ui.PadSettings
import com.nguyen.flexpad.ui.SettingsScreen
import com.nguyen.flexpad.ui.TesterScreen

/**
 * Activity chính của FlexPad — chỉ dùng cho điều khiển phiên, kiểm thử,
 * cài đặt và chẩn đoán. Tay cầm khi chơi là cửa sổ overlay không giành focus
 * do PadOverlayService quản lý (không phải Activity này).
 * Bốn tab: Chính / Kiểm thử / Cài đặt / Chẩn đoán.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.init(this)
        ButtonHaptics.init(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings by AppSettings.flow(context).collectAsState(initial = PadSettings())
    LaunchedEffect(settings.hapticsEnabled, settings.hapticIntensity) {
        ButtonHaptics.enabled = settings.hapticsEnabled
        ButtonHaptics.intensity = settings.hapticIntensity
    }

    var tab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Chính", "Kiểm thử", "Cài đặt", "Chẩn đoán")

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Text("FlexPad", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Tay cầm Xbox ảo cho OPPO Find N5 ở chế độ Flex Mode.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        TabRow(selectedTabIndex = tab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title, maxLines = 1, softWrap = false) }
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (tab) {
                0 -> HomeTab(activity)
                1 -> TesterScreen()
                2 -> SettingsScreen()
                else -> DiagnosticsScreen()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeTab(activity: Activity?) {
    val context = LocalContext.current
    val shizukuStatus by ShizukuBridge.status.collectAsState()
    val serviceConnected by ShizukuBridge.serviceConnected.collectAsState()
    // v1.4: đọc tư thế qua Activity (đường đi v0.1 đã kiểm chứng trên Find N5);
    // đồng thời công bố vào FoldStateStore để tab Chẩn đoán đọc chéo.
    val flexState by if (activity != null) {
        remember(activity) {
            FoldObserver.observeActivity(activity)
                .onEach { FoldStateStore.activityState.value = it }
        }.collectAsState(initial = FlexState())
    } else {
        FoldStateStore.activityState.collectAsState()
    }
    val overlayMessage by PadSessionState.overlayMessage.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Shizuku", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(shizukuStatus, style = MaterialTheme.typography.bodyMedium)
            Text(
                "UserService: ${if (serviceConnected) "ĐÃ KẾT NỐI" else "chưa kết nối"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ShizukuBridge.requestPermission() }) {
                    Text("Xin quyền Shizuku", maxLines = 1, softWrap = false)
                }
                Button(
                    enabled = !serviceConnected,
                    onClick = { ShizukuBridge.bindService() }
                ) {
                    Text("Kết nối UserService", maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = serviceConnected,
                onClick = { ShizukuBridge.unbindService() }
            ) {
                Text("Ngắt kết nối UserService", maxLines = 1, softWrap = false)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Phiên tay cầm", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Quy trình chơi: Kết nối UserService → Tạo thiết bị (tab Chẩn đoán) → " +
                    "Bắt đầu phiên → mở game → gập Flex Mode, pad tự hiện ở nửa dưới.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            var startNote by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    // Phản hồi ngay tại chỗ: trước đây bấm mà không thấy gì đổi nên tưởng nút chết.
                    startNote = if (!Settings.canDrawOverlays(context)) {
                        "Đã gửi lệnh bắt đầu phiên. NHƯNG máy chưa cấp quyền 'Hiển thị trên các " +
                            "ứng dụng khác' nên pad sẽ CHƯA hiện được — sang tab Cài đặt bấm " +
                            "'Cấp quyền overlay' trước, rồi gập máy Flex Mode pad mới hiện."
                    } else {
                        "Đã gửi lệnh bắt đầu phiên. Pad chỉ hiện khi máy ở Flex Mode (gập ~90°) " +
                            "hoặc khi bật 'Luôn hiện pad'. Xem thẻ 'Trạng thái phiên trực tiếp' " +
                            "ngay bên dưới để biết pad đang hiện hay ẩn và vì sao."
                    }
                    PadOverlayService.start(context)
                }) {
                    Text("Bắt đầu phiên", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { PadOverlayService.stop(context) }) {
                    Text("Dừng phiên (hủy thiết bị)", maxLines = 1, softWrap = false)
                }
            }
            if (startNote.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(startNote, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Trạng thái phiên trực tiếp", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val scope = rememberCoroutineScope()
            var deviceStatusText by remember { mutableStateOf("(đang kiểm tra…)") }
            LaunchedEffect(serviceConnected) {
                deviceStatusText = ShizukuBridge.deviceStatus()
            }
            val overlayVisible by PadSessionState.overlayVisible.collectAsState()
            val sessionHeadline by PadSessionState.sessionHeadline.collectAsState()
            Text("Thiết bị ảo: $deviceStatusText", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Overlay pad: ${if (overlayVisible) "ĐANG HIỆN" else "đang ẩn"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(sessionHeadline, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                scope.launch { deviceStatusText = ShizukuBridge.deviceStatus() }
            }) { Text("Làm mới trạng thái", maxLines = 1, softWrap = false) }
        }
    }

    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Tư thế gập hiện tại", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(flexState.describe(), style = MaterialTheme.typography.bodyMedium)
            if (overlayMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(overlayMessage!!, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
