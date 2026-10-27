package com.nguyen.flexpad.diag

import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tiện ích chẩn đoán dùng chung cho Diagnostics và các backend.
 * Nguyên tắc: mọi lỗi đều được trả ra dạng chữ kèm errno/exception thật —
 * không có try/catch rỗng, không có thông báo chung chung.
 */
object DiagTools {

    // Ánh xạ số errno -> tên hằng số (EACCES, EPERM...) bằng cách quét OsConstants.
    private val errnoNames: Map<Int, String> by lazy {
        val map = HashMap<Int, String>()
        for (field in OsConstants::class.java.fields) {
            val name = field.name
            if (name.length > 1 && name[0] == 'E' && name == name.uppercase()) {
                try {
                    val value = field.getInt(null)
                    if (!map.containsKey(value)) map[value] = name
                } catch (ignored: Exception) {
                    // Field không phải int tĩnh — bỏ qua, đây là quét phản chiếu phòng thủ.
                }
            }
        }
        map
    }

    /** Tên errno, ví dụ 13 -> "EACCES". Không rõ thì trả "errno <số>". */
    fun errnoName(errno: Int): String = errnoNames[errno] ?: "errno $errno"

    /** Kết quả chạy một lệnh hệ thống. */
    data class ExecResult(val exitCode: Int, val output: String)

    /**
     * Chạy lệnh trong tiến trình hiện tại (khi ở UserService là uid shell/root).
     * Có timeout cứng để không treo giao diện; lỗi khởi chạy lệnh cũng được trả ra.
     *
     * QUAN TRỌNG (bài học từ test thật 2026-10-26): phải XẢ output ĐỒNG THỜI trong
     * lúc chờ tiến trình. Bản cũ gọi waitFor() xong mới đọc inputStream — lệnh nào
     * in quá dung lượng ống pipe (~64KB) như dumpsys input hay logcat sẽ bị chặn
     * ghi, không bao giờ thoát, và hết timeout oan (từng khiến bước kiểm chứng (d)
     * thất bại dù thiết bị UHID/uinput đều đã tạo thành công ở mức kernel).
     */
    fun exec(args: List<String>, timeoutMs: Long = 4000): ExecResult {
        return try {
            val proc = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            try {
                proc.outputStream.close()
            } catch (ignored: Exception) {
                // Lệnh không có stdin — bỏ qua, không ảnh hưởng việc xả output.
            }
            val capture = StringBuffer()
            val drainer = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(capture) {
                            if (capture.length < MAX_CAPTURE_CHARS) {
                                capture.append(line).append('\n')
                            }
                        }
                    }
                } catch (ignored: Exception) {
                    // Pipe đóng khi tiến trình bị hủy — bình thường.
                }
            }
            drainer.isDaemon = true
            drainer.start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                drainer.join(600)
                ExecResult(-998, "(lệnh quá thời gian ${timeoutMs}ms, đã hủy)")
            } else {
                drainer.join(1500)
                ExecResult(proc.exitValue(), capture.toString())
            }
        } catch (e: Exception) {
            ExecResult(-999, "Không chạy được lệnh ${args.joinToString(" ")}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Giới hạn dung lượng output giữ lại (2 triệu ký tự — đủ cho dumpsys input đầy đủ). */
    private const val MAX_CAPTURE_CHARS = 2_000_000

    /** Đọc file text; nếu lỗi, trả về chuỗi mô tả lỗi kèm errno (không ném ngoại lệ). */
    fun readFileText(path: String): String {
        return try {
            File(path).readText().trim()
        } catch (e: Exception) {
            val errno = (e as? android.system.ErrnoException)?.errno
            "(đọc lỗi: ${e.javaClass.simpleName}: ${e.message}" +
                (if (errno != null) ", errno=$errno ${errnoName(errno)}" else "") + ")"
        }
    }

    /** Kiểm tra đường dẫn có tồn tại không (dùng cho /dev/uhid, /dev/uinput...). */
    fun exists(path: String): Boolean = File(path).exists()

    /** Chuyển byte thành hex để in nhật ký (giới hạn số byte hiển thị). */
    fun hex(bytes: ByteArray, max: Int = 32): String {
        val n = minOf(bytes.size, max)
        val sb = StringBuilder()
        for (i in 0 until n) sb.append("%02X ".format(bytes[i]))
        if (bytes.size > max) sb.append("... (${bytes.size} byte)")
        return sb.toString().trim()
    }

    /** Đọc một prop hệ thống qua phản chiếu android.os.SystemProperties; lỗi thì trả mô tả lỗi. */
    fun systemProperty(key: String): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.ifBlank { "(trống)" } ?: "(null)"
        } catch (e: Exception) {
            // Dự phòng: hỏi qua getprop (trong tiến trình shell lệnh này luôn có).
            val r = exec(listOf("getprop", key), 1500)
            val v = r.output.trim()
            if (v.isNotEmpty()) v else "(không đọc được: ${e.javaClass.simpleName})"
        }
    }
}
