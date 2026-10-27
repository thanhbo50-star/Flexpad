package com.nguyen.flexpad.diag

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Thu thập báo cáo chẩn đoán (mục 3.1 của đặc tả).
 * Hàm collect() chạy TRONG tiến trình Shizuku UserService nên mọi thông tin
 * (uid, SELinux context, khả năng mở /dev/uhid...) phản ánh đúng tiến trình
 * sẽ tạo tay cầm ảo. Mọi bước lỗi đều in lỗi thật kèm errno.
 */
object Diagnostics {

    fun collect(): String {
        val sb = StringBuilder()
        sb.appendLine("===== FlexPad — BÁO CÁO CHẨN ĐOÁN =====")

        // ---------- 1. Danh tính tiến trình service ----------
        sb.appendLine()
        sb.appendLine("[1] Tiến trình UserService")
        try {
            sb.appendLine("uid=${Os.getuid()} gid=${Os.getgid()} pid=${Os.getpid()}")
        } catch (e: Exception) {
            sb.appendLine("Không đọc được uid/gid: ${e.javaClass.simpleName}: ${e.message}")
        }
        sb.appendLine("SELinux context của service (/proc/self/attr/current): " +
            DiagTools.readFileText("/proc/self/attr/current"))
        val enforce = DiagTools.readFileText("/sys/fs/selinux/enforce")
        val getenforce = DiagTools.exec(listOf("getenforce"), 1500).output.trim()
        sb.appendLine("SELinux enforce: /sys/fs/selinux/enforce=$enforce ; getenforce=$getenforce")

        // ---------- 2. Hai nút thiết bị ----------
        sb.appendLine()
        sb.appendLine("[2] Nút thiết bị /dev/uhid và /dev/uinput")
        for (path in listOf("/dev/uhid", "/dev/uinput")) {
            sb.appendLine("$path tồn tại: ${DiagTools.exists(path)}")
        }
        val ls = DiagTools.exec(listOf("ls", "-lZ", "/dev/uhid", "/dev/uinput"), 2000)
        sb.appendLine("ls -lZ (exit=${ls.exitCode}):")
        sb.appendLine(ls.output.trim().ifBlank { "(không có output)" })

        // ---------- 3. Mở thử trực tiếp ----------
        sb.appendLine()
        sb.appendLine("[3] Mở thử bằng Os.open (chỉ mở rồi đóng, chưa tạo thiết bị)")
        sb.appendLine(openTest("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC, "O_RDWR|O_CLOEXEC"))
        sb.appendLine(openTest("/dev/uinput", OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, "O_RDWR|O_NONBLOCK|O_CLOEXEC"))

        // ---------- 4. Kernel ----------
        sb.appendLine()
        sb.appendLine("[4] Kernel")
        try {
            val u = Os.uname()
            sb.appendLine("uname: sysname=${u.sysname} release=${u.release} version=${u.version} machine=${u.machine}")
        } catch (e: Exception) {
            sb.appendLine("uname lỗi: ${e.javaClass.simpleName}: ${e.message}")
        }
        sb.appendLine(kernelConfigReport())

        // ---------- 5. Thông tin máy / ROM ----------
        sb.appendLine()
        sb.appendLine("[5] Thiết bị và ROM")
        val propKeys = listOf(
            "ro.product.model", "ro.product.manufacturer", "ro.build.display.id",
            "ro.build.version.release", "ro.build.version.sdk",
            "ro.build.version.oplusrom", "ro.build.version.oplusrom.display",
            "ro.oplus.version", "ro.build.fingerprint"
        )
        for (key in propKeys) {
            sb.appendLine("$key = ${DiagTools.systemProperty(key)}")
        }

        // ---------- 6. logcat: các dòng avc denied ----------
        sb.appendLine()
        sb.appendLine("[6] logcat — dòng 'avc: denied' gần đây (tối đa 30 dòng)")
        val logcat = DiagTools.exec(listOf("logcat", "-d", "-b", "all", "-t", "800"), 10000)
        if (logcat.exitCode == -999 || logcat.exitCode == -998) {
            sb.appendLine("Không đọc được logcat: ${logcat.output}")
        } else {
            val avc = logcat.output.lineSequence()
                .filter { it.contains("avc:") && it.contains("denied") }
                .take(30)
                .toList()
            if (avc.isEmpty()) {
                sb.appendLine("(không thấy dòng avc denied nào trong 800 dòng log gần nhất — " +
                    "nếu vừa bị chặn, hãy bấm Tạo thiết bị trước rồi chạy lại chẩn đoán ngay)")
            } else {
                avc.forEach { sb.appendLine(it) }
            }
        }

        // ---------- 7. dumpsys input ----------
        sb.appendLine()
        sb.appendLine("[7] dumpsys input — danh sách thiết bị đầu vào hiện tại")
        sb.appendLine(dumpsysInputDeviceList())

        sb.appendLine()
        sb.appendLine("===== HẾT BÁO CÁO =====")
        return sb.toString()
    }

    /** Mở thử một nút thiết bị rồi đóng ngay; báo errno chính xác nếu thất bại. */
    private fun openTest(path: String, flags: Int, flagsText: String): String {
        return try {
            val fd = Os.open(path, flags, 0)
            Os.close(fd)
            "Mở $path ($flagsText): THÀNH CÔNG"
        } catch (e: ErrnoException) {
            "Mở $path ($flagsText): THẤT BẠI — errno=${e.errno} (${DiagTools.errnoName(e.errno)}): ${e.message}"
        } catch (e: Exception) {
            "Mở $path ($flagsText): THẤT BẠI — ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Đọc /proc/config.gz (nếu kernel cho phép) và trích các cờ liên quan UHID/uinput/HID. */
    private fun kernelConfigReport(): String {
        val file = File("/proc/config.gz")
        if (!file.exists()) return "CONFIG: /proc/config.gz không tồn tại trên máy này."
        val wanted = listOf("CONFIG_UHID", "CONFIG_INPUT_UINPUT", "CONFIG_HID", "CONFIG_HID_GENERIC")
        return try {
            val text = GZIPInputStream(FileInputStream(file)).bufferedReader().readText()
            val found = StringBuilder("CONFIG từ /proc/config.gz:")
            var any = false
            for (line in text.lineSequence()) {
                val key = line.substringBefore('=').removePrefix("# ").removeSuffix(" is not set").trim()
                if (key in wanted) {
                    found.appendLine().append("  $line")
                    any = true
                }
            }
            if (!any) found.append(" (không tìm thấy các cờ UHID/UINPUT/HID trong config)")
            found.toString()
        } catch (e: Exception) {
            "CONFIG: đọc /proc/config.gz lỗi — ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Liệt kê thiết bị đầu vào từ dumpsys input: với mỗi thiết bị, lấy dòng tên
     * và các dòng khóa (VendorId, ProductId, Sources, KeyLayoutFile, trục)
     * để đối chiếu trước/sau khi tạo tay cầm ảo. Cũng dùng cho bước kiểm chứng (d).
     */
    fun dumpsysInputDeviceList(): String {
        val r = DiagTools.exec(listOf("dumpsys", "input"), 12000)
        if (r.exitCode == -999 || r.exitCode == -998) {
            return "Không chạy được dumpsys input: ${r.output}"
        }
        val lines = r.output.lines()
        val sb = StringBuilder()
        var deviceCount = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val header = DEVICE_HEADER_REGEX.matchEntire(line.trim())
            if (header != null) {
                deviceCount++
                sb.appendLine(line.trim())
                // In kèm tối đa 24 dòng thuộc tính ngay sau tiêu đề thiết bị.
                var j = i + 1
                var printed = 0
                while (j < lines.size && printed < 24) {
                    val sub = lines[j]
                    if (DEVICE_HEADER_REGEX.matches(sub.trim())) break
                    val t = sub.trim()
                    if (t.startsWith("Descriptor:") || t.startsWith("Location:") ||
                        t.startsWith("UniqueId:") || t.startsWith("VendorId:") ||
                        t.startsWith("ProductId:") || t.startsWith("Sources:") ||
                        t.startsWith("KeyboardType:") || t.startsWith("KeyLayoutFile:") ||
                        t.startsWith("Axis ") || t.contains("X:") || t.contains("Y:")
                    ) {
                        sb.appendLine("    $t")
                        printed++
                    }
                    j++
                }
                i = j
            } else {
                i++
            }
        }
        if (deviceCount == 0) {
            return "dumpsys input chạy được (exit=${r.exitCode}) nhưng không trích được thiết bị nào. " +
                "Output thô (500 ký tự đầu): ${r.output.take(500)}"
        }
        return "Tổng số thiết bị đầu vào: $deviceCount\n" + sb.toString().trimEnd()
    }

    /**
     * Kiểm chứng sau khi tạo thiết bị: tìm "Xbox Wireless Controller" trong dumpsys input
     * và trích khối thông tin của nó. Trả về chuỗi bắt đầu bằng "THẤY" hoặc "KHÔNG THẤY".
     */
    fun verifyGamepadInDumpsys(): String {
        val r = DiagTools.exec(listOf("dumpsys", "input"), 12000)
        if (r.exitCode == -999 || r.exitCode == -998) {
            return "KHÔNG KIỂM CHỨNG ĐƯỢC: dumpsys input lỗi — ${r.output}"
        }
        val idx = r.output.indexOf("Xbox Wireless Controller")
        if (idx < 0) {
            return "KHÔNG THẤY 'Xbox Wireless Controller' trong dumpsys input — thiết bị CHƯA được hệ thống đăng ký."
        }
        // Trích khoảng 40 dòng từ tiêu đề thiết bị để xem VendorId/ProductId/Sources/KeyLayoutFile/trục.
        val start = r.output.lastIndexOf('\n', idx).coerceAtLeast(0)
        val excerpt = r.output.substring(start, minOf(r.output.length, start + 2200))
        return "THẤY 'Xbox Wireless Controller' trong dumpsys input. Trích đoạn:\n$excerpt"
    }

    private val DEVICE_HEADER_REGEX = Regex("^\\d+: .+")
}
