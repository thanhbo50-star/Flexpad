package com.nguyen.flexpad.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nguyen.flexpad.haptics.ButtonHaptics
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Cần analog của FlexPad.
 *  - Cần nằm trong "giếng" tròn (vành ngoài + lòng trong), núm có mặt lõm — theo ảnh mẫu.
 *  - Chế độ tâm cố định: tâm là giữa giếng. Chế độ tâm theo điểm chạm (floating):
 *    tâm là nơi ngón tay chạm xuống, kéo tương đối từ đó.
 *  - Thả tay: cần tự chạy về tâm trong ~110ms, phát vị trí suốt đường về và
 *    rung click nhẹ khi chạm tâm (học từ v0.2).
 *  - Khi đẩy sát vành, mỗi nấc 45° phát một tick haptic (có chống dội 90ms).
 * Giá trị phát ra qua [onMove] đã chuẩn hoá -1..1 (chưa áp deadzone — PadController lo).
 */
@Composable
fun AnalogStick(
    label: String,
    size: Dp,
    theme: PadTheme,
    floating: Boolean,
    modifier: Modifier = Modifier,
    onStickClick: (() -> Unit)? = null,
    onMove: (Float, Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    var sizePx by remember { mutableFloatStateOf(0f) }
    var knobX by remember { mutableFloatStateOf(0f) } // lệch của núm so với tâm giếng (px)
    var knobY by remember { mutableFloatStateOf(0f) }
    var originX by remember { mutableFloatStateOf(0f) } // tâm kéo (chế độ floating)
    var originY by remember { mutableFloatStateOf(0f) }
    var lastSector by remember { mutableStateOf(-1) }
    var lastDetentAt by remember { mutableStateOf(0L) }
    val returnAnim = remember { Animatable(0f) }

    // Hành trình thật của tay cầm Xbox: (đường kính giếng 34mm - núm 19mm) / 2 = 7.5mm,
    // tức 0.44 bán kính giếng — mép núm vừa chạm vành khi đẩy hết cỡ.
    fun maxOffset(): Float = (sizePx / 2f) * 0.44f

    fun report() {
        val m = maxOffset()
        if (m <= 0f) return
        onMove((knobX / m).coerceIn(-1f, 1f), (knobY / m).coerceIn(-1f, 1f))
    }

    fun detentCheck() {
        val m = maxOffset()
        if (m <= 0f) return
        val mag = hypot(knobX, knobY) / m
        if (mag > 0.92f) {
            var deg = Math.toDegrees(atan2(knobX.toDouble(), -knobY.toDouble()))
            if (deg < 0) deg += 360.0
            val sector = ((deg + 22.5) / 45.0).toInt() % 8
            val now = System.currentTimeMillis()
            if (sector != lastSector && now - lastDetentAt > 90) {
                lastSector = sector
                lastDetentAt = now
                ButtonHaptics.stickDetent()
            }
        } else {
            lastSector = -1
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .onSizeChanged { sizePx = it.width.toFloat() }
            .clip(CircleShape)
            .background(theme.stickWell)
            .border(2.dp, theme.panelBorder, CircleShape)
            .pointerInput(label, floating) {
                var activeId: PointerId? = null
                var downAtMs = 0L
                var maxDispRatio = 0f

                fun dragTo(px: Float, py: Float) {
                    val cx = sizePx / 2f
                    val tx = px - cx - originX
                    val ty = py - cx - originY
                    val m = maxOffset()
                    val len = hypot(tx, ty)
                    val cl = if (len > m && len > 0f) m / len else 1f
                    knobX = tx * cl
                    knobY = ty * cl
                    if (m > 0f) maxDispRatio = maxOf(maxDispRatio, hypot(knobX, knobY) / m)
                    report()
                    detentCheck()
                }

                fun releaseStick() {
                    val wasDeflected = hypot(knobX, knobY) > maxOffset() * 0.25f
                    val sx = knobX
                    val sy = knobY
                    val sox = originX
                    val soy = originY
                    scope.launch {
                        returnAnim.snapTo(0f)
                        returnAnim.animateTo(1f, tween(110)) {
                            knobX = sx * (1f - value)
                            knobY = sy * (1f - value)
                            originX = sox * (1f - value)
                            originY = soy * (1f - value)
                            report()
                        }
                        knobX = 0f
                        knobY = 0f
                        originX = 0f
                        originY = 0f
                        onMove(0f, 0f)
                        lastSector = -1
                        if (wasDeflected) ButtonHaptics.stickCenter()
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
                                    downAtMs = System.currentTimeMillis()
                                    maxDispRatio = 0f
                                    scope.launch { returnAnim.stop() }
                                    val cx = sizePx / 2f
                                    if (floating) {
                                        originX = change.position.x - cx
                                        originY = change.position.y - cx
                                    } else {
                                        originX = 0f
                                        originY = 0f
                                    }
                                    dragTo(change.position.x, change.position.y)
                                }
                                change.pressed && change.id == activeId && change.positionChanged() -> {
                                    change.consume()
                                    dragTo(change.position.x, change.position.y)
                                }
                                wentUp && change.id == activeId -> {
                                    activeId = null
                                    change.consume()
                                    // Chạm nhanh, gần như không kéo = NHẤN CẦN (L3/R3) như tay cầm thật.
                                    val heldMs = System.currentTimeMillis() - downAtMs
                                    if (onStickClick != null && heldMs < 280 && maxDispRatio < 0.2f) {
                                        onStickClick()
                                    }
                                    releaseStick()
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Lòng giếng trong.
        Box(
            modifier = Modifier
                .size(size * 0.82f)
                .clip(CircleShape)
                .background(shade(theme.stickWell, 0.8f))
                .border(1.dp, shade(theme.panelBorder, 0.6f), CircleShape)
        )
        // Vòng tâm kéo (chỉ hiện ở chế độ floating khi đang kéo).
        if (floating && (originX != 0f || originY != 0f)) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(originX.roundToInt(), originY.roundToInt()) }
                    .size(size * 0.5f)
                    .clip(CircleShape)
                    .border(1.dp, theme.accent, CircleShape)
            )
        }
        // Núm cần: đĩa ngoài + mặt lõm trong.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (originX + knobX).roundToInt(),
                        (originY + knobY).roundToInt()
                    )
                }
                .size(size * 0.56f)
                .clip(CircleShape)
                .background(theme.stickKnob)
                .border(2.dp, theme.panelBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(size * 0.38f)
                    .clip(CircleShape)
                    .background(shade(theme.stickKnob, 0.88f))
                    .border(1.dp, shade(theme.panelBorder, 0.7f), CircleShape)
            )
        }
    }
}
