package com.nguyen.flexpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nguyen.flexpad.PadController
import com.nguyen.flexpad.gamepad.PadButton
import com.nguyen.flexpad.haptics.ButtonHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mặt tay cầm FlexPad — bố cục theo KÍCH THƯỚC THẬT của Xbox Wireless Controller
 * (mặt tay cầm 153 x 102 mm; vị trí các cụm theo sơ đồ chính thức của Microsoft):
 *  - Cần trái ở (42, 42), D-pad ngay dưới nó ở (42, 76) — như tay cầm thật.
 *  - Cụm ABXY tâm (121, 42), nút Ø17.5mm, khoảng cách tâm 20.5mm.
 *  - Cần phải (97, 72). Nút Xbox Ø21mm ở (76.5, 24); View/Menu Ø12mm hai bên.
 *  - Vai L1/L2/R1/R2 là các phím dài 34x11mm dọc cạnh trên.
 *  - L3/R3 = NHẤN XUỐNG CẦN (chạm nhanh vào cần) — đúng như tay cầm thật,
 *    không còn nút L3/R3 rời chật chội như bản cũ.
 * Mọi phần tử được đặt theo tọa độ mm nhân hệ số s = chiều cao vùng / 102mm.
 * Vùng hiển thị (nửa dưới máy gập) thường RỘNG hơn tỉ lệ 153:102 nên các cụm
 * được NEO: cụm trái neo cạnh trái, cụm phải neo cạnh phải, cụm giữa neo giữa —
 * kích thước từng nút giữ đúng mm thật, chỉ khoảng cách giữa các cụm giãn ra
 * như hai tay cầm xa nhau. [onFirstTouch] phục vụ kiểm chứng focus ở overlay.
 */
@Composable
fun PadScreen(
    settings: PadSettings,
    modifier: Modifier = Modifier,
    onFirstTouch: () -> Unit = {}
) {
    val theme = settings.resolveTheme()
    val scope = rememberCoroutineScope()
    var firstTouchFired by remember { mutableStateOf(false) }

    val firstTouchObserver = Modifier.pointerInput("first-touch") {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    if (!firstTouchFired) {
                        firstTouchFired = true
                        onFirstTouch()
                    }
                    break
                }
            }
        }
    }

    // Nhấn cần = L3/R3: phát một xung bấm 70ms kèm haptic điểm kích hoạt.
    fun stickClick(button: PadButton) {
        scope.launch {
            ButtonHaptics.actuation()
            PadController.setButton(button, true)
            delay(70)
            PadController.setButton(button, false)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .background(theme.panelBg)
            .border(width = 1.5.dp, color = theme.panelBorder)
            .then(firstTouchObserver)
    ) {
        val regionW = maxWidth.value
        val regionH = maxHeight.value
        // Hệ số mm -> dp. Ưu tiên khớp chiều cao (102mm); nếu vùng quá hẹp thì khớp chiều rộng.
        var s = regionH / 102f
        if (153f * s > regionW) s = regionW / 153f
        val faceH = 102f * s
        val topOff = ((regionH - faceH) / 2f).coerceAtLeast(0f)

        fun mm(v: Float): Dp = (v * s).dp
        // Neo trái: x là tâm tính từ cạnh trái. Neo phải: x là tâm tính từ cạnh phải.
        // Neo giữa: x là độ lệch tâm so với tâm vùng.
        fun leftX(centerMm: Float): Dp = mm(centerMm)
        fun rightX(centerFromRightMm: Float): Dp = (regionW - centerFromRightMm * s).dp
        fun centerX(offsetMm: Float): Dp = (regionW / 2f + offsetMm * s).dp
        fun y(centerMm: Float): Dp = (topOff + centerMm * s).dp

        Box(modifier = Modifier.fillMaxSize()) {
            // ---------- Vai (cạnh trên) ----------
            // Trái: L2 tâm cách trái 24mm, L1 cách trái 60mm. Phải đối xứng.
            ShoulderButton(
                "L2", mm(34f), mm(11f), theme,
                modifier = Modifier.offset(leftX(24f) - mm(17f), y(6f) - mm(5.5f))
            ) { pressed -> PadController.setTriggers(leftPressed = pressed, rightPressed = null) }
            ShoulderButton(
                "L1", mm(34f), mm(11f), theme,
                modifier = Modifier.offset(leftX(60f) - mm(17f), y(6f) - mm(5.5f))
            ) { pressed -> PadController.setButton(PadButton.LB, pressed) }
            ShoulderButton(
                "R1", mm(34f), mm(11f), theme,
                modifier = Modifier.offset(rightX(60f) - mm(17f), y(6f) - mm(5.5f))
            ) { pressed -> PadController.setButton(PadButton.RB, pressed) }
            ShoulderButton(
                "R2", mm(34f), mm(11f), theme,
                modifier = Modifier.offset(rightX(24f) - mm(17f), y(6f) - mm(5.5f))
            ) { pressed -> PadController.setTriggers(leftPressed = null, rightPressed = pressed) }

            // ---------- Cần analog (vẽ trước, nút nhỏ vẽ sau để thắng vùng chồng lấn) ----------
            AnalogStick(
                label = "stick-left",
                size = mm(34f),
                theme = theme,
                floating = settings.floatingStick,
                modifier = Modifier.offset(leftX(42f) - mm(17f), y(42f) - mm(17f)),
                onStickClick = { stickClick(PadButton.L3) }
            ) { x, yv -> PadController.setLeftStick(x, yv, settings.deadzone) }
            AnalogStick(
                label = "stick-right",
                size = mm(34f),
                theme = theme,
                floating = settings.floatingStick,
                modifier = Modifier.offset(rightX(56f) - mm(17f), y(72f) - mm(17f)),
                onStickClick = { stickClick(PadButton.R3) }
            ) { x, yv -> PadController.setRightStick(x, yv, settings.deadzone) }
            // Cần phải: tâm mặt thật x=97mm -> cách cạnh phải 153-97 = 56mm. Đúng số ở trên.

            // ---------- D-pad (dưới cần trái, như tay cầm thật) ----------
            DPadView(
                size = mm(34f),
                theme = theme,
                modifier = Modifier.offset(leftX(42f) - mm(17f), y(76f) - mm(17f))
            ) { hat -> PadController.setHat(hat) }

            // ---------- Cụm ABXY (kim cương, tâm mặt thật (121,42) -> cách phải 32mm) ----------
            val abxyCx = rightX(32f)
            val abxyCy = 42f
            val abxyR = 20.5f
            val btnD = mm(17.5f)
            RoundPadButton(
                "Y", btnD, theme,
                modifier = Modifier.offset(abxyCx - mm(8.75f), y(abxyCy - abxyR) - mm(8.75f))
            ) { pressed -> PadController.setButton(PadButton.Y, pressed) }
            RoundPadButton(
                "X", btnD, theme,
                modifier = Modifier.offset(abxyCx - mm(abxyR) - mm(8.75f), y(abxyCy) - mm(8.75f))
            ) { pressed -> PadController.setButton(PadButton.X, pressed) }
            RoundPadButton(
                "B", btnD, theme,
                modifier = Modifier.offset(abxyCx + mm(abxyR) - mm(8.75f), y(abxyCy) - mm(8.75f))
            ) { pressed -> PadController.setButton(PadButton.B, pressed) }
            RoundPadButton(
                "A", btnD, theme,
                modifier = Modifier.offset(abxyCx - mm(8.75f), y(abxyCy + abxyR) - mm(8.75f))
            ) { pressed -> PadController.setButton(PadButton.A, pressed) }

            // ---------- Cụm giữa: View / Xbox / Menu ----------
            RoundPadButton(
                "✕", mm(21f), theme, accentBg = true,
                modifier = Modifier.offset(centerX(0f) - mm(10.5f), y(24f) - mm(10.5f))
            ) { pressed -> PadController.setButton(PadButton.GUIDE, pressed) }
            SmallPadButton(
                "View", mm(13f), theme,
                modifier = Modifier.offset(centerX(-20f) - mm(6.5f), y(28f) - mm(6.5f))
            ) { pressed -> PadController.setButton(PadButton.VIEW, pressed) }
            SmallPadButton(
                "Menu", mm(13f), theme,
                modifier = Modifier.offset(centerX(20f) - mm(6.5f), y(28f) - mm(6.5f))
            ) { pressed -> PadController.setButton(PadButton.MENU, pressed) }
        }
    }
}
