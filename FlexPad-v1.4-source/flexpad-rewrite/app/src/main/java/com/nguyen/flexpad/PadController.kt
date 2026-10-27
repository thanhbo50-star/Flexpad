package com.nguyen.flexpad

import com.nguyen.flexpad.gamepad.GamepadState
import com.nguyen.flexpad.gamepad.HatValue
import com.nguyen.flexpad.gamepad.PadButton
import com.nguyen.flexpad.gamepad.ReportEncoder
import com.nguyen.flexpad.shizuku.ShizukuBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Giữ trạng thái tay cầm phía app và đẩy xuống UserService qua AIDL oneway.
 *
 * Quy tắc độ trễ (mục 3.5 của đặc tả):
 *  - Chỉ gửi khi trạng thái THAY ĐỔI (so sánh snapshot trước/sau).
 *  - Gộp tối đa ~250 lần/giây: một thread riêng kiểm tra cờ "bẩn" mỗi 4ms;
 *    khi cần đang kéo, các thay đổi liên tục tự tạo thành dòng gửi liên tục.
 *  - Khi pad ẩn/ngừng: gửi đúng một trạng thái trung tính rồi thôi.
 * [uiState] là bản sao dành cho giao diện (panel kiểm thử hiển thị trực tiếp).
 */
object PadController {

    private val lock = Any()

    @Volatile
    private var state: GamepadState = GamepadState.NEUTRAL

    private val _uiState = MutableStateFlow(GamepadState.NEUTRAL)
    val uiState: StateFlow<GamepadState> = _uiState

    private var lastSent: GamepadState = GamepadState.NEUTRAL
    private val dirty = AtomicBoolean(false)

    @Volatile
    private var senderThread: Thread? = null

    fun currentState(): GamepadState = state

    /** Bắt đầu thread gửi (gọi khi Foreground Service chạy). */
    fun start() {
        if (senderThread != null) return
        val thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                if (dirty.getAndSet(false)) {
                    val snapshot = synchronized(lock) { state }
                    if (snapshot != lastSent) {
                        ShizukuBridge.sendState(ReportEncoder.encodeStatePacket(snapshot))
                        lastSent = snapshot
                    }
                }
                try {
                    Thread.sleep(4) // ~250 Hz tối đa
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "flexpad-ipc-sender")
        thread.isDaemon = true
        senderThread = thread
        thread.start()
    }

    /** Dừng thread gửi và đưa tay cầm về trung tính. */
    fun stop() {
        resetToNeutral()
        senderThread?.interrupt()
        senderThread = null
    }

    /** Cập nhật bản sao giao diện + đánh dấu bẩn. PHẢI gọi khi đang giữ lock. */
    private fun publishLocked() {
        _uiState.value = state
        dirty.set(true)
    }

    /** Cập nhật toàn bộ trạng thái (dùng khi cần đặt nhiều trường cùng lúc). */
    fun update(newState: GamepadState) {
        synchronized(lock) {
            state = newState
            publishLocked()
        }
    }

    fun setButton(button: PadButton, pressed: Boolean) {
        synchronized(lock) {
            state = state.withButton(button, pressed)
            publishLocked()
        }
    }

    fun setHat(hat: Int) {
        synchronized(lock) {
            state = state.copy(hat = hat)
            publishLocked()
        }
    }

    fun setDpad(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        setHat(HatValue.fromDirections(up, down, left, right))
    }

    fun setLeftStick(x: Float, y: Float, deadzone: Float = 0.08f) {
        synchronized(lock) {
            state = state.copy(
                leftX = GamepadState.axisFromFloat(x, deadzone),
                leftY = GamepadState.axisFromFloat(y, deadzone)
            )
            publishLocked()
        }
    }

    fun setRightStick(x: Float, y: Float, deadzone: Float = 0.08f) {
        synchronized(lock) {
            state = state.copy(
                rightX = GamepadState.axisFromFloat(x, deadzone),
                rightY = GamepadState.axisFromFloat(y, deadzone)
            )
            publishLocked()
        }
    }

    /** Trigger số: pressed = true -> 1023, false -> 0 (cấu trúc sẵn sàng cho analog). */
    fun setTriggers(leftPressed: Boolean?, rightPressed: Boolean?) {
        synchronized(lock) {
            state = state.copy(
                leftTrigger = leftPressed?.let { if (it) 1023 else 0 } ?: state.leftTrigger,
                rightTrigger = rightPressed?.let { if (it) 1023 else 0 } ?: state.rightTrigger
            )
            publishLocked()
        }
    }

    /** Gửi trạng thái trung tính ngay (khi pad bị ẩn mà thiết bị vẫn sống). */
    fun resetToNeutral() {
        synchronized(lock) {
            state = GamepadState.NEUTRAL
            publishLocked()
        }
    }
}
