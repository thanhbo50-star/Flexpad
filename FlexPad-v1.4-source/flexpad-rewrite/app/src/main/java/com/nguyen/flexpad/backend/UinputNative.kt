package com.nguyen.flexpad.backend

/**
 * Cầu nối JNI tới libflexpad_uinput.so (mã C++ ở app/src/main/cpp/uinput_jni.cpp)
 * phục vụ backend B2 (/dev/uinput).
 * Việc nạp thư viện có thể thất bại trong tiến trình UserService (đường dẫn native
 * khác tiến trình app), nên ensureLoaded thử lần lượt: nạp theo tên, rồi nạp theo
 * đường dẫn tuyệt đối do app truyền sang qua AIDL initPaths(). Mọi thất bại đều
 * được giữ lại trong [loadError] để hiển thị lên màn hình — không nuốt lỗi.
 */
object UinputNative {

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    fun ensureLoaded(nativeLibDir: String?): Boolean {
        if (isLoaded) return true
        val errors = StringBuilder()
        try {
            System.loadLibrary("flexpad_uinput")
            isLoaded = true
            return true
        } catch (e: Throwable) {
            errors.append("loadLibrary(flexpad_uinput): ${e.message}; ")
        }
        if (!nativeLibDir.isNullOrBlank()) {
            try {
                System.load("$nativeLibDir/libflexpad_uinput.so")
                isLoaded = true
                return true
            } catch (e: Throwable) {
                errors.append("load($nativeLibDir/libflexpad_uinput.so): ${e.message}")
            }
        }
        loadError = errors.toString()
        return false
    }

    @JvmStatic
    external fun nativeCreate(): String

    @JvmStatic
    external fun nativeSendState(packet: ByteArray): Boolean

    @JvmStatic
    external fun nativeDestroy(): String
}
