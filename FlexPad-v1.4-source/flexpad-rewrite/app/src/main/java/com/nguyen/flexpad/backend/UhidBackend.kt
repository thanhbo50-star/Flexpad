package com.nguyen.flexpad.backend

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.nguyen.flexpad.diag.DiagTools
import com.nguyen.flexpad.gamepad.GamepadState
import com.nguyen.flexpad.gamepad.HidDescriptor
import com.nguyen.flexpad.gamepad.ReportEncoder
import com.nguyen.flexpad.gamepad.Uhid
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Backend B1 — tạo tay cầm ảo qua /dev/uhid, thuần Kotlin bằng android.system.Os.
 * Chạy trong tiến trình Shizuku UserService.
 *
 * Các yêu cầu bắt buộc từ phụ lục (đã áp dụng):
 *  - Hằng số sự kiện lấy từ Uhid (đã đối chiếu linux/uhid.h): CREATE2 = 11, INPUT2 = 12.
 *  - Mở bằng Os.open với O_RDWR | O_CLOEXEC (không dùng RandomAccessFile "rw" như v0.1).
 *  - Mỗi lần ghi đều ghi đủ EVENT_SIZE (4376) byte, phần thừa bằng 0.
 *  - create() báo cáo RIÊNG từng bước: (a) mở fd, (b) ghi CREATE2, (c) sự kiện
 *    START/OPEN từ kernel. Việc kiểm chứng bằng dumpsys input (bước d) do
 *    FlexPadUserService thực hiện ngay sau create().
 *  - Có thread đọc fd để xử lý START/STOP/OPEN/CLOSE/GET_REPORT/SET_REPORT/OUTPUT
 *    và trả lời GET_REPORT — thiếu thread này thiết bị có thể treo.
 */
class UhidBackend(
    private val logEvent: (String) -> Unit,
    private val onRumble: (Int, Int) -> Unit = { _, _ -> }
) : InputBackend {

    override val id: BackendId = BackendId.UHID

    @Volatile private var fd: FileDescriptor? = null
    private val writeLock = Any()

    @Volatile private var readerThread: Thread? = null
    @Volatile private var sawStart = false
    @Volatile private var sawOpen = false

    // Báo cáo input gần nhất — dùng để trả lời GET_REPORT của kernel.
    @Volatile private var lastReport: ByteArray = ReportEncoder.encodeHidReport(GamepadState.NEUTRAL)

    // OUTPUT report gần nhất từ game (rumble) — sẽ nối vào Vibrator ở MỐC 5.
    @Volatile var lastOutputReportHex: String? = null
        private set

    override fun isCreated(): Boolean = fd != null

    override fun create(): String {
        val sb = StringBuilder()
        if (fd != null) {
            sb.appendLine("B1/UHID: thiết bị đã được tạo trước đó, bỏ qua.")
            return sb.toString()
        }

        // ---- Bước (a): mở /dev/uhid ----
        val openedFd: FileDescriptor = try {
            Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            sb.appendLine(
                "B1/UHID bước (a) MỞ /dev/uhid: THẤT BẠI — ErrnoException errno=${e.errno} " +
                    "(${DiagTools.errnoName(e.errno)}): ${e.message}"
            )
            sb.appendLine("B1/UHID: dừng tại bước (a).")
            return sb.toString()
        } catch (e: Exception) {
            sb.appendLine("B1/UHID bước (a) MỞ /dev/uhid: THẤT BẠI — ${e.javaClass.simpleName}: ${e.message}")
            sb.appendLine("B1/UHID: dừng tại bước (a).")
            return sb.toString()
        }
        sb.appendLine("B1/UHID bước (a) MỞ /dev/uhid: OK")
        fd = openedFd

        // Thread đọc phải chạy TRƯỚC khi ghi CREATE2 để không bỏ lỡ START/OPEN.
        startReader(openedFd)

        // ---- Bước (b): ghi UHID_CREATE2 ----
        val descriptor = HidDescriptor.REPORT_DESCRIPTOR
        val ev = ByteBuffer.allocate(Uhid.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        ev.putInt(0, Uhid.UHID_CREATE2)
        putFixedString(ev, Uhid.CREATE2_NAME_OFF, Uhid.DEVICE_NAME, 128)
        putFixedString(ev, Uhid.CREATE2_PHYS_OFF, "flexpad/uhid0", 64)
        putFixedString(ev, Uhid.CREATE2_UNIQ_OFF, "flexpad-uhid-0001", 64)
        ev.putShort(Uhid.CREATE2_RD_SIZE_OFF, descriptor.size.toShort())
        ev.putShort(Uhid.CREATE2_BUS_OFF, Uhid.BUS_BLUETOOTH.toShort())
        ev.putInt(Uhid.CREATE2_VENDOR_OFF, Uhid.VENDOR_MICROSOFT)
        ev.putInt(Uhid.CREATE2_PRODUCT_OFF, Uhid.PRODUCT_XBOX_WIRELESS)
        ev.putInt(Uhid.CREATE2_VERSION_OFF, 0)
        ev.putInt(Uhid.CREATE2_COUNTRY_OFF, 0)
        ev.position(Uhid.CREATE2_RD_DATA_OFF)
        ev.put(descriptor)
        try {
            writeAll(openedFd, ev.array())
            sb.appendLine(
                "B1/UHID bước (b) GHI UHID_CREATE2 (${Uhid.EVENT_SIZE} byte, " +
                    "descriptor ${descriptor.size} byte): OK"
            )
        } catch (e: ErrnoException) {
            sb.appendLine(
                "B1/UHID bước (b) GHI UHID_CREATE2: THẤT BẠI — errno=${e.errno} " +
                    "(${DiagTools.errnoName(e.errno)}): ${e.message}"
            )
            closeQuietly()
            sb.appendLine("B1/UHID: dừng tại bước (b).")
            return sb.toString()
        }

        // ---- Bước (c): chờ kernel phát UHID_START (tối đa ~900ms) ----
        var waited = 0
        while (!sawStart && waited < 900) {
            Thread.sleep(30)
            waited += 30
        }
        sb.appendLine(
            "B1/UHID bước (c) SỰ KIỆN KERNEL: UHID_START=${if (sawStart) "ĐÃ NHẬN" else "CHƯA NHẬN sau ${waited}ms"}, " +
                "UHID_OPEN=${if (sawOpen) "ĐÃ NHẬN" else "chưa (bình thường nếu chưa app nào mở thiết bị)"}"
        )
        if (!sawStart) {
            sb.appendLine(
                "B1/UHID: CẢNH BÁO — chưa thấy UHID_START. Thiết bị có thể vẫn xuất hiện trong " +
                    "dumpsys input; kiểm chứng ở bước (d) sẽ quyết định."
            )
        }
        sb.appendLine("B1/UHID: tạo thiết bị xong (chờ kiểm chứng dumpsys ở bước (d)).")
        return sb.toString()
    }

    override fun sendState(state: GamepadState): Boolean {
        val f = fd ?: return false
        val report = ReportEncoder.encodeHidReport(state)
        lastReport = report
        val ev = ByteBuffer.allocate(Uhid.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        ev.putInt(0, Uhid.UHID_INPUT2)
        ev.putShort(Uhid.INPUT2_SIZE_OFF, report.size.toShort())
        ev.position(Uhid.INPUT2_DATA_OFF)
        ev.put(report)
        return try {
            writeAll(f, ev.array())
            true
        } catch (e: Exception) {
            logEvent("UHID_INPUT2 ghi lỗi: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override fun destroy(): String {
        val sb = StringBuilder()
        val f = fd
        if (f == null) {
            sb.appendLine("B1/UHID: chưa có thiết bị để hủy.")
            return sb.toString()
        }
        // Gửi UHID_DESTROY trước khi đóng fd (đóng fd cũng tự hủy thiết bị,
        // nhưng gửi DESTROY rõ ràng giúp kernel dọn ngay và nhật ký đầy đủ).
        try {
            val ev = ByteBuffer.allocate(Uhid.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            ev.putInt(0, Uhid.UHID_DESTROY)
            writeAll(f, ev.array())
            sb.appendLine("B1/UHID: đã gửi UHID_DESTROY.")
        } catch (e: Exception) {
            sb.appendLine("B1/UHID: ghi UHID_DESTROY lỗi (${e.javaClass.simpleName}: ${e.message}) — vẫn đóng fd.")
        }
        closeQuietly()
        sb.appendLine("B1/UHID: đã đóng /dev/uhid.")
        return sb.toString()
    }

    // ---------- Nội bộ ----------

    private fun putFixedString(buf: ByteBuffer, offset: Int, value: String, capacity: Int) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val n = minOf(bytes.size, capacity - 1)
        for (i in 0 until n) buf.put(offset + i, bytes[i])
        // Phần còn lại đã là 0 do ByteBuffer.allocate.
    }

    /** Ghi toàn bộ buffer, lặp cho tới khi đủ (write có thể ghi một phần). */
    private fun writeAll(f: FileDescriptor, bytes: ByteArray) {
        synchronized(writeLock) {
            var off = 0
            while (off < bytes.size) {
                val n = Os.write(f, bytes, off, bytes.size - off)
                if (n <= 0) throw IllegalStateException("Os.write trả về $n tại offset $off")
                off += n
            }
        }
    }

    private fun closeQuietly() {
        val f = fd
        fd = null
        sawStart = false
        sawOpen = false
        if (f != null) {
            try {
                Os.close(f)
            } catch (e: Exception) {
                logEvent("Đóng /dev/uhid lỗi: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        readerThread = null
    }

    private fun startReader(f: FileDescriptor) {
        val thread = Thread({
            val buf = ByteArray(Uhid.EVENT_SIZE)
            while (fd === f) {
                val n = try {
                    Os.read(f, buf, 0, buf.size)
                } catch (e: ErrnoException) {
                    logEvent("Thread đọc UHID dừng: errno=${e.errno} (${DiagTools.errnoName(e.errno)}): ${e.message}")
                    break
                } catch (e: Exception) {
                    logEvent("Thread đọc UHID dừng: ${e.javaClass.simpleName}: ${e.message}")
                    break
                }
                if (n <= 0) {
                    logEvent("Thread đọc UHID: read trả về $n, kết thúc.")
                    break
                }
                handleKernelEvent(f, buf, n)
            }
        }, "flexpad-uhid-reader")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    private fun handleKernelEvent(f: FileDescriptor, buf: ByteArray, length: Int) {
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val type = bb.getInt(0)
        when (type) {
            Uhid.UHID_START -> {
                val flags = if (length >= 12) bb.getLong(4) else 0L
                sawStart = true
                logEvent("Kernel -> UHID_START (dev_flags=$flags): thiết bị đã khởi động trong HID core.")
            }
            Uhid.UHID_STOP -> {
                sawStart = false
                logEvent("Kernel -> UHID_STOP: thiết bị bị dừng.")
            }
            Uhid.UHID_OPEN -> {
                sawOpen = true
                logEvent("Kernel -> UHID_OPEN: một tiến trình (thường là InputReader) đã mở thiết bị.")
            }
            Uhid.UHID_CLOSE -> {
                sawOpen = false
                logEvent("Kernel -> UHID_CLOSE: tiến trình cuối đã đóng thiết bị.")
            }
            Uhid.UHID_GET_REPORT -> {
                val id = bb.getInt(Uhid.GET_REPORT_ID_OFF)
                val rnum = buf[Uhid.GET_REPORT_RNUM_OFF].toInt() and 0xFF
                val rtype = buf[Uhid.GET_REPORT_RTYPE_OFF].toInt() and 0xFF
                logEvent("Kernel -> UHID_GET_REPORT id=$id rnum=$rnum rtype=$rtype: trả lời bằng báo cáo input gần nhất.")
                // Trả lời: err = 0, data = báo cáo input gần nhất (kèm Report ID).
                val reply = ByteBuffer.allocate(Uhid.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                reply.putInt(0, Uhid.UHID_GET_REPORT_REPLY)
                reply.putInt(4, id)
                reply.putShort(8, 0)
                reply.putShort(10, lastReport.size.toShort())
                reply.position(Uhid.GET_REPORT_REPLY_DATA_OFF)
                reply.put(lastReport)
                try {
                    writeAll(f, reply.array())
                } catch (e: Exception) {
                    logEvent("Trả lời GET_REPORT lỗi: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Uhid.UHID_SET_REPORT -> {
                val id = bb.getInt(4)
                val size = bb.getShort(Uhid.SET_REPORT_SIZE_OFF).toInt() and 0xFFFF
                val rnum = buf[8].toInt() and 0xFF
                val rtype = buf[9].toInt() and 0xFF
                val data = buf.copyOfRange(
                    Uhid.SET_REPORT_DATA_OFF,
                    minOf(buf.size, Uhid.SET_REPORT_DATA_OFF + size)
                )
                logEvent("Kernel -> UHID_SET_REPORT id=$id rnum=$rnum rtype=$rtype size=$size data=${DiagTools.hex(data)}: đã xác nhận err=0.")
                // Xbox thật dùng SET_REPORT cho cấu hình rumble/LED — mốc này chỉ xác nhận.
                val reply = ByteBuffer.allocate(Uhid.EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                reply.putInt(0, Uhid.UHID_SET_REPORT_REPLY)
                reply.putInt(4, id)
                reply.putShort(8, 0)
                try {
                    writeAll(f, reply.array())
                } catch (e: Exception) {
                    logEvent("Trả lời SET_REPORT lỗi: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Uhid.UHID_OUTPUT -> {
                val size = bb.getShort(Uhid.OUTPUT_SIZE_OFF).toInt() and 0xFFFF
                val rtype = buf[Uhid.OUTPUT_RTYPE_OFF].toInt() and 0xFF
                val data = buf.copyOfRange(4, minOf(buf.size, 4 + size))
                val hex = DiagTools.hex(data, 16)
                lastOutputReportHex = hex
                logEvent("Kernel -> UHID_OUTPUT rtype=$rtype size=$size data=$hex (rumble từ game).")
                // Rumble (MỐC 5, best-effort): descriptor hiện không khai báo output
                // report riêng nên kernel hiếm khi gửi OUTPUT; nếu có, lấy hai byte
                // độ mạnh theo bố cục phổ biến [id, mạnh, yếu] và chuyển về app.
                if (size >= 3) {
                    val strong = data[1].toInt() and 0xFF
                    val weak = data[2].toInt() and 0xFF
                    try {
                        onRumble(strong, weak)
                    } catch (e: Exception) {
                        logEvent("Chuyển rumble về app lỗi: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
            else -> logEvent("Kernel -> sự kiện lạ type=$type (${Uhid.eventName(type)}), độ dài đọc được=$length.")
        }
    }
}
