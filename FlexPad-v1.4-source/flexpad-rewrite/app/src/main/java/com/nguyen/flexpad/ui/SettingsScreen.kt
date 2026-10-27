package com.nguyen.flexpad.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nguyen.flexpad.shizuku.ShizukuBridge
import kotlinx.coroutines.launch

/**
 * Màn hình Cài đặt (MỐC 4 + 5): quyền overlay, hiển thị pad, deadzone,
 * chế độ tâm cần, haptic, theme (6 preset + tự phối màu bằng thanh trượt RGB),
 * và công cụ đưa game lên nửa trên (freeform qua Shizuku + hướng dẫn).
 * Mọi thay đổi lưu ngay vào DataStore qua AppSettings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppSettings.flow(context).collectAsState(initial = PadSettings())

    fun save(transform: (PadSettings) -> PadSettings) {
        scope.launch { AppSettings.update(context, transform) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Cài đặt", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        // ---------- Hiển thị pad ----------
        SectionTitle("Hiển thị tay cầm (overlay)")
        var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        Text(
            "Quyền hiển thị trên ứng dụng khác: ${if (overlayGranted) "ĐÃ CẤP" else "CHƯA CẤP"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }) { Text("Cấp quyền overlay") }
            OutlinedButton(onClick = { overlayGranted = Settings.canDrawOverlays(context) }) {
                Text("Làm mới")
            }
        }
        SettingSwitch(
            label = "Luôn hiện pad (kể cả khi không gập Flex)",
            checked = settings.alwaysShowPad,
            onCheckedChange = { v -> save { it.copy(alwaysShowPad = v) } }
        )
        Text("Tỉ lệ kích thước pad: ${(settings.padScale * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.padScale,
            onValueChange = { v -> save { it.copy(padScale = v) } },
            valueRange = 0.85f..1.2f
        )

        // ---------- Điều khiển ----------
        SectionTitle("Điều khiển")
        Text("Vùng chết (deadzone) cần analog: ${(settings.deadzone * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.deadzone,
            onValueChange = { v -> save { it.copy(deadzone = v) } },
            valueRange = 0f..0.3f
        )
        SettingSwitch(
            label = "Tâm cần theo điểm chạm (thả trống = tâm cố định ở giữa)",
            checked = settings.floatingStick,
            onCheckedChange = { v -> save { it.copy(floatingStick = v) } }
        )

        // ---------- Haptic ----------
        SectionTitle("Rung haptic")
        SettingSwitch(
            label = "Bật rung khi bấm/kéo",
            checked = settings.hapticsEnabled,
            onCheckedChange = { v -> save { it.copy(hapticsEnabled = v) } }
        )
        Text("Cường độ rung: ${(settings.hapticIntensity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.hapticIntensity,
            onValueChange = { v -> save { it.copy(hapticIntensity = v) } },
            valueRange = 0.3f..1.5f
        )

        // ---------- Theme ----------
        SectionTitle("Theme tay cầm")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (preset in PadThemes.presets) {
                FilterChip(
                    selected = settings.themeId == preset.id,
                    onClick = { save { it.copy(themeId = preset.id) } },
                    label = { Text(preset.name, maxLines = 1, softWrap = false) }
                )
            }
            FilterChip(
                selected = settings.themeId == "custom",
                onClick = { save { it.copy(themeId = "custom") } },
                label = { Text("Tự phối màu", maxLines = 1, softWrap = false) }
            )
        }
        if (settings.themeId == "custom") {
            Spacer(Modifier.height(6.dp))
            ColorEditor("Màu nền pad", settings.customPanelBg) { v -> save { it.copy(customPanelBg = v) } }
            ColorEditor("Màu nút", settings.customButtonBg) { v -> save { it.copy(customButtonBg = v) } }
            ColorEditor("Màu nhấn (accent)", settings.customAccent) { v -> save { it.copy(customAccent = v) } }
            ColorEditor("Màu chữ", settings.customText) { v -> save { it.copy(customText = v) } }
        }

        // ---------- Game ở nửa trên ----------
        SectionTitle("Đưa game lên nửa trên")
        var freeformLog by remember { mutableStateOf("") }
        Text(
            "Ba phương án theo thứ tự nên thử: (a) chia đôi màn hình của ColorOS — mở game, " +
                "vào Đa nhiệm, chọn 'Chia màn hình' rồi chọn app thứ hai cho nửa dưới (pad overlay " +
                "sẽ phủ đúng vùng dưới bản lề); (b) cửa sổ tự do (freeform) qua nút dưới đây — " +
                "một số game chống resize sẽ không chịu, khi đó quay về (a) hoặc (c); " +
                "(c) game toàn màn hình và pad phủ nửa dưới (HUD dưới có thể bị che).",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = {
            scope.launch { freeformLog = ShizukuBridge.applyFreeformSettings() }
        }) { Text("Bật hỗ trợ freeform qua Shizuku") }
        if (freeformLog.isNotEmpty()) {
            Text(freeformLog, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Bộ chỉnh một màu bằng 3 thanh trượt R/G/B. */
@Composable
private fun ColorEditor(title: String, argb: Int, onChange: (Int) -> Unit) {
    val color = Color(argb)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(color, RoundedCornerShape(6.dp))
                    .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
            )
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        ColorChannelSlider("R", (color.red * 255).toInt()) { v ->
            onChange(Color(v / 255f, color.green, color.blue).toArgb())
        }
        ColorChannelSlider("G", (color.green * 255).toInt()) { v ->
            onChange(Color(color.red, v / 255f, color.blue).toArgb())
        }
        ColorChannelSlider("B", (color.blue * 255).toInt()) { v ->
            onChange(Color(color.red, color.green, v / 255f).toArgb())
        }
    }
}

@Composable
private fun ColorChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(value.toString(), style = MaterialTheme.typography.bodySmall)
    }
}
