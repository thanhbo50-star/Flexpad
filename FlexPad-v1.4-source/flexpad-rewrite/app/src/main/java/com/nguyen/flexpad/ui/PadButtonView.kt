package com.nguyen.flexpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nguyen.flexpad.gamepad.HatValue
import com.nguyen.flexpad.haptics.ButtonHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Nút bấm nền tảng của FlexPad — xử lý đa chạm theo từng pointerId riêng,
 * hành trình phím 3 giai đoạn đồng bộ haptic (xem ButtonHaptics):
 * chạm (tick) -> giữ qua điểm kích hoạt 38ms (mới đăng ký input + CLICK/THUD)
 * -> nhả (nảy về). Chạm nhanh dưới 38ms vẫn phát trọn một chu kỳ bấm tối thiểu 26ms
 * để game không bỏ lỡ thao tác.
 */
@Composable
fun PadPressable(
    modifier: Modifier = Modifier,
    theme: PadTheme,
    shape: Shape = RoundedCornerShape(16.dp),
    label: String,
    onPressChanged: (Boolean) -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var actuationJob by remember { mutableStateOf<Job?>(null) }
    var releaseJob by remember { mutableStateOf<Job?>(null) }

    // Đăng ký input (đúng thời điểm điểm kích hoạt) + haptic đi kèm.
    fun actuate() {
        ButtonHaptics.actuation()
        onPressChanged(true)
    }

    val gestureModifier = Modifier.pointerInput(label) {
        val activeIds = mutableSetOf<androidx.compose.ui.input.pointer.PointerId>()
        var actuated = false

        // Xử lý khi pointer cuối cùng rời nút. (Dùng chuyển trạng pressed/
        // previousPressed của PointerInputChange thay cho event type từng change.)
        fun onAllReleased() {
            pressed = false
            actuationJob?.cancel()
            if (!actuated) {
                // Chạm nhanh: vẫn phát một chu kỳ bấm đầy đủ.
                actuated = true
                actuate()
                releaseJob = scope.launch {
                    delay(26)
                    onPressChanged(false)
                    ButtonHaptics.release()
                }
            } else {
                onPressChanged(false)
                ButtonHaptics.release()
            }
        }

        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                for (change in event.changes) {
                    val wentDown = change.pressed && !change.previousPressed
                    val wentUp = !change.pressed && change.previousPressed
                    if (wentDown) {
                        if (activeIds.add(change.id)) {
                            change.consume()
                            if (activeIds.size == 1) {
                                releaseJob?.cancel()
                                pressed = true
                                actuated = false
                                ButtonHaptics.touchTick()
                                actuationJob = scope.launch {
                                    delay(38)
                                    if (pressed && !actuated) {
                                        actuated = true
                                        actuate()
                                    }
                                }
                            }
                        }
                    } else if (wentUp) {
                        if (activeIds.remove(change.id)) {
                            change.consume()
                            if (activeIds.isEmpty()) onAllReleased()
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .then(gestureModifier)
            .graphicsLayer {
                scaleX = if (pressed) 0.94f else 1f
                scaleY = if (pressed) 0.94f else 1f
                translationY = if (pressed) 2f else 0f
            }
            .background(if (pressed) theme.buttonPressed else theme.buttonBg, shape)
            .border(1.5.dp, if (pressed) theme.accent else theme.panelBorder, shape),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Nút tròn (ABXY, Guide, L3/R3...). */
@Composable
fun RoundPadButton(
    text: String,
    diameter: Dp,
    theme: PadTheme,
    modifier: Modifier = Modifier,
    accentBg: Boolean = false,
    onPressChanged: (Boolean) -> Unit
) {
    PadPressable(
        modifier = modifier.size(diameter),
        theme = theme,
        shape = CircleShape,
        label = "round-$text",
        onPressChanged = onPressChanged
    ) {
        if (accentBg) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(theme.accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                ButtonLabel(text, diameter, theme, onAccent = true)
            }
        } else {
            ButtonLabel(text, diameter, theme, onAccent = false)
        }
    }
}

@Composable
private fun ButtonLabel(text: String, diameter: Dp, theme: PadTheme, onAccent: Boolean) {
    Text(
        text = text,
        color = if (onAccent) theme.buttonBg else theme.buttonText,
        fontSize = (diameter.value * 0.36f).sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false
    )
}

/** Nút vai L1/L2/R1/R2 — hình chữ nhật bo góc, chữ nhỏ. */
@Composable
fun ShoulderButton(
    text: String,
    width: Dp,
    height: Dp,
    theme: PadTheme,
    modifier: Modifier = Modifier,
    onPressChanged: (Boolean) -> Unit
) {
    PadPressable(
        modifier = modifier.size(width = width, height = height),
        theme = theme,
        shape = RoundedCornerShape(12.dp),
        label = "shoulder-$text",
        onPressChanged = onPressChanged
    ) {
        Text(
            text = text,
            color = theme.buttonText,
            fontSize = (height.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Nút nhỏ (View, Menu, L3, R3). */
@Composable
fun SmallPadButton(
    text: String,
    diameter: Dp,
    theme: PadTheme,
    modifier: Modifier = Modifier,
    onPressChanged: (Boolean) -> Unit
) {
    PadPressable(
        modifier = modifier.size(diameter),
        theme = theme,
        shape = CircleShape,
        label = "small-$text",
        onPressChanged = onPressChanged
    ) {
        Text(
            text = text,
            color = theme.buttonText,
            fontSize = (diameter.value * 0.30f).sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * D-pad: một vùng chạm duy nhất, tính hướng từ vị trí ngón tay (trượt được
 * giữa các hướng như D-pad thật), phát giá trị hat 0..8 qua [onHatChanged].
 */
@Composable
fun DPadView(
    size: Dp,
    theme: PadTheme,
    modifier: Modifier = Modifier,
    onHatChanged: (Int) -> Unit
) {
    var hat by remember { mutableStateOf(HatValue.NEUTRAL) }
    fun setHat(newHat: Int) {
        if (newHat != hat) {
            hat = newHat
            onHatChanged(newHat)
        }
    }

    val up = hat == HatValue.UP || hat == HatValue.UP_LEFT || hat == HatValue.UP_RIGHT
    val down = hat == HatValue.DOWN || hat == HatValue.DOWN_LEFT || hat == HatValue.DOWN_RIGHT
    val left = hat == HatValue.LEFT || hat == HatValue.UP_LEFT || hat == HatValue.DOWN_LEFT
    val right = hat == HatValue.RIGHT || hat == HatValue.UP_RIGHT || hat == HatValue.DOWN_RIGHT

    Box(
        modifier = modifier
            .size(size)
            .pointerInput("dpad") {
                var activeId: androidx.compose.ui.input.pointer.PointerId? = null

                fun releaseDpad() {
                    if (activeId != null) {
                        activeId = null
                        setHat(HatValue.NEUTRAL)
                        ButtonHaptics.release()
                    }
                }

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            val wentDown = change.pressed && !change.previousPressed
                            val wentUp = !change.pressed && change.previousPressed
                            when {
                                wentDown && activeId == null -> {
                                    activeId = change.id
                                    change.consume()
                                    ButtonHaptics.touchTick()
                                    updateHat(change.position.x, change.position.y, size.toPx()) { setHat(it) }
                                }
                                change.pressed && change.id == activeId && change.positionChanged() -> {
                                    change.consume()
                                    updateHat(change.position.x, change.position.y, size.toPx()) { setHat(it) }
                                }
                                wentUp && change.id == activeId -> {
                                    change.consume()
                                    releaseDpad()
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Tấm đế hình dấu cộng.
        Box(
            modifier = Modifier
                .size(width = size, height = size * 0.36f)
                .background(shade(theme.buttonBg, 0.92f), RoundedCornerShape(10.dp))
                .border(1.5.dp, theme.panelBorder, RoundedCornerShape(10.dp))
        )
        Box(
            modifier = Modifier
                .size(width = size * 0.36f, height = size)
                .background(shade(theme.buttonBg, 0.92f), RoundedCornerShape(10.dp))
                .border(1.5.dp, theme.panelBorder, RoundedCornerShape(10.dp))
        )
        // 4 mũi tên chỉ hướng; hướng đang hoạt động dùng màu accent.
        DPadArrow("▲", up, theme, Modifier.align(Alignment.TopCenter))
        DPadArrow("▼", down, theme, Modifier.align(Alignment.BottomCenter))
        DPadArrow("◀", left, theme, Modifier.align(Alignment.CenterStart))
        DPadArrow("▶", right, theme, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun DPadArrow(glyph: String, active: Boolean, theme: PadTheme, modifier: Modifier) {
    Text(
        text = glyph,
        color = if (active) theme.accent else theme.buttonText,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

/** Tính hat từ vị trí chạm trong vùng D-pad (toạ độ px, tâm là giữa vùng). */
private fun updateHat(x: Float, y: Float, sizePx: Float, apply: (Int) -> Unit) {
    val cx = sizePx / 2f
    val dx = x - cx
    val dy = y - cx
    val r = hypot(dx, dy)
    if (r < sizePx * 0.14f) {
        apply(HatValue.NEUTRAL)
        return
    }
    // Góc theo chiều kim đồng hồ từ hướng lên: 0° = lên, 90° = phải.
    var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
    if (deg < 0) deg += 360.0
    val sector = ((deg + 22.5) / 45.0).toInt() % 8 // 0=lên,1=lên-phải,... 7=lên-trái
    apply(sector + 1) // hat 1..8
}
