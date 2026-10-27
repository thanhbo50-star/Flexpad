package com.nguyen.flexpad.overlay

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Trạng thái phiên pad dùng chung giữa PadOverlayService, MainActivity và
 * màn hình Chẩn đoán (hiển thị thông điệp overlay + kết quả kiểm chứng focus).
 */
object PadSessionState {
    /** Thông điệp về overlay (vd: thiếu quyền hiển thị trên ứng dụng khác). */
    val overlayMessage = MutableStateFlow<String?>(null)

    /** Kết quả dumpsys window tại lần chạm pad gần nhất — bằng chứng focus còn ở game. */
    val lastFocusCheck = MutableStateFlow("(chưa kiểm tra — hãy chạm vào pad khi đang mở game)")

    /** Overlay pad có đang hiển thị hay không (service cập nhật theo thời gian thực). */
    val overlayVisible = MutableStateFlow(false)

    /** Một dòng tóm tắt trạng thái phiên hiện tại cho tab Chính. */
    val sessionHeadline = MutableStateFlow("(phiên chưa bắt đầu)")
}

/**
 * Máy chủ vòng đời cho ComposeView nằm NGOÀI Activity (trong cửa sổ overlay).
 * ComposeView ở overlay không có Activity nên phải tự gắn LifecycleOwner,
 * SavedStateRegistryOwner và ViewModelStoreOwner — thiếu một trong ba là crash.
 */
class OverlayComposeHost(context: Context) : androidx.lifecycle.LifecycleOwner,
    SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry = savedStateController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    val composeView: ComposeView = ComposeView(context)

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
    }

    fun start() {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
        store.clear()
    }
}
