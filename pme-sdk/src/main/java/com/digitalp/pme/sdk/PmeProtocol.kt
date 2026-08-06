package com.digitalp.pme.sdk

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * PME 协议帧
 *
 * 固定帧头 12B + 数据区(4B头 + buffer) + CRC16：
 * ┌──────┬──────┬──────────┬──────┬──────┬──────────┬──────────┬───────┬───────┬──────────┬────────────┬──────────┐
 * │ 0x55 │ 0xAA │ dataLen  │ src  │ dst  │ seqNo    │ status   │ prodId│ proto │ cmdId    │ params     │ CRC16    │
 * │  1B  │  1B  │  2B LE   │  1B  │  1B  │  4B LE   │  2B LE   │  1B   │  1B   │  2B LE   │ 0~124B    │  2B      │
 * └──────┴──────┴──────────┴──────┴──────┴──────────┴──────────┴───────┴───────┴──────────┴────────────┴──────────┘
 *
 * dataLen = 4(prodId+proto+cmdId) + params.size，不含 CRC；总长 = 12 + dataLen + 2。
 */
data class PmeFrame(
    val header1: Int,
    val header2: Int,
    val dataLen: Int,
    val srcAddr: Int,
    val dstAddr: Int,
    val seqNo: Int,
    val status: Int,
    val productId: Int,
    val protocolType: Int,
    val cmdId: Int,
    val params: ByteArray,
    val crc: Int,
    val crcValid: Boolean
)

/** 0x2000 病人信息（协议 5.4：从机→主机上报；主机写仅为实验） */
data class PmePatientInfo(
    val patientNo: String,
    val sex: Int,          // 0 男 / 1 女
    val type: Int,         // 0 成人 / 1 新生儿 / 2 儿童
    val heightCm: Int,
    val weightKg: Int,
    val age: Int,
    val dataId: String     // 数据 ID，11 位手机号等
)

/**
 * 每连接一份的编解码器：粘包缓冲与序号相互独立，避免多连接串包。
 */
class PmeCodec {
    private val buffer = ByteArray(2048)
    private var bufferLen = 0
    private var reqSeqNo = 0
    private var ackSeqNo = 0

    fun clearBuffer() {
        bufferLen = 0
    }

    /** 喂入原始字节流，返回解析出的完整帧列表。 */
    fun feed(bytes: ByteArray): List<PmeFrame> {
        val frames = mutableListOf<PmeFrame>()

        if (bytes.size > buffer.size - bufferLen) {
            Log.w(TAG, "缓冲区溢出，丢弃旧数据")
            bufferLen = 0
        }
        if (bytes.size > buffer.size - bufferLen) {
            Log.w(TAG, "单包过大，丢弃")
            return frames
        }
        System.arraycopy(bytes, 0, buffer, bufferLen, bytes.size)
        bufferLen += bytes.size

        while (bufferLen >= PmeProtocol.MIN_FRAME_SIZE) {
            var frameStart = -1
            for (i in 0..bufferLen - 2) {
                if (buffer[i] == PmeProtocol.HEADER1 && buffer[i + 1] == PmeProtocol.HEADER2) {
                    frameStart = i
                    break
                }
            }

            if (frameStart == -1) {
                bufferLen = 0
                break
            }

            if (frameStart > 0) {
                val remaining = bufferLen - frameStart
                System.arraycopy(buffer, frameStart, buffer, 0, remaining)
                bufferLen = remaining
            }

            if (bufferLen < PmeProtocol.FIXED_HEADER) break

            val dataLen = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            if (dataLen < PmeProtocol.DATA_HEAD || dataLen > 128) {
                Log.w(TAG, "无效 dataLen=$dataLen，跳过帧头")
                System.arraycopy(buffer, 2, buffer, 0, bufferLen - 2)
                bufferLen -= 2
                continue
            }

            val totalFrameSize = PmeProtocol.FIXED_HEADER + dataLen + PmeProtocol.CRC_SIZE
            if (bufferLen < totalFrameSize) break

            val rawFrame = ByteArray(totalFrameSize)
            System.arraycopy(buffer, 0, rawFrame, 0, totalFrameSize)
            frames.add(PmeProtocol.parseFrame(rawFrame))

            val remaining = bufferLen - totalFrameSize
            if (remaining > 0) {
                System.arraycopy(buffer, totalFrameSize, buffer, 0, remaining)
            }
            bufferLen = remaining
        }

        return frames
    }

    /** 构建应答：cmdId 与请求相同，参数为请求 seqNo，地址对调。 */
    fun buildAck(reqFrame: PmeFrame): ByteArray {
        val params = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(reqFrame.seqNo).array()
        val seq = ackSeqNo++
        return PmeProtocol.buildFrame(
            srcAddr = reqFrame.dstAddr,
            dstAddr = reqFrame.srcAddr,
            productId = reqFrame.productId,
            protocolType = reqFrame.protocolType,
            cmdId = reqFrame.cmdId,
            status = PmeProtocol.STATUS_RESPONSE,
            seqNo = seq,
            params = params
        )
    }

    fun buildRequest(cmdId: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val seqNo = reqSeqNo++
        return PmeProtocol.buildFrame(
            srcAddr = PmeProtocol.ADDR_MASTER.toInt() and 0xFF,
            dstAddr = PmeProtocol.ADDR_SLAVE.toInt() and 0xFF,
            productId = PmeProtocol.PRODUCT_ID.toInt() and 0xFF,
            protocolType = 0,
            cmdId = cmdId,
            status = PmeProtocol.STATUS_REQUEST,
            seqNo = seqNo,
            params = params
        )
    }

    companion object {
        private const val TAG = "PME_PROTO"
    }
}

object PmeProtocol {
    private const val TAG = "PME_PROTO"

    const val HEADER1: Byte = 0x55.toByte()
    const val HEADER2: Byte = 0xAA.toByte()

    /** 固定帧头长度（到 status 为止，含 dataLen 字段） */
    const val FIXED_HEADER = 12
    /** 数据区头：productId + protocolType + cmdId */
    const val DATA_HEAD = 4
    const val CRC_SIZE = 2
    const val MIN_FRAME_SIZE = FIXED_HEADER + DATA_HEAD + CRC_SIZE // 空参数最小帧

    const val ADDR_MASTER: Byte = 0x00
    const val ADDR_SLAVE: Byte = 0x01
    const val PRODUCT_ID: Byte = 0x01

    const val CMD_DEVICE_INFO: Int = 0x1000
    const val CMD_DEVICE_STATUS: Int = 0x1001
    const val CMD_BLE_NAME: Int = 0x1100
    const val CMD_PATIENT_INFO: Int = 0x2000
    const val CMD_PHYSIO_DATA: Int = 0x2001
    /** 主机主动建链 */
    const val CMD_LINK: Int = 0x8001
    /** 主机心跳保活（约每 2s 发送） */
    const val CMD_KEEPALIVE: Int = 0x8002

    const val STATUS_REQUEST: Int = 0x0002
    const val STATUS_RESPONSE: Int = 0x0004
    const val STATUS_NOTIFY: Int = 0x0008

    private val crcTable = IntArray(256).apply {
        for (i in indices) {
            var crc = i
            for (j in 0 until 8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
            this[i] = crc
        }
    }

    internal fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            val index = (crc xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc ushr 8) xor crcTable[index]
        }
        return crc
    }

    internal fun parseFrame(raw: ByteArray): PmeFrame {
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        val h1 = buf.get().toInt() and 0xFF
        val h2 = buf.get().toInt() and 0xFF
        val dataLen = buf.short.toInt() and 0xFFFF
        val src = buf.get().toInt() and 0xFF
        val dst = buf.get().toInt() and 0xFF
        val seqNo = buf.int
        val status = buf.short.toInt() and 0xFFFF
        val prodId = buf.get().toInt() and 0xFF
        val proto = buf.get().toInt() and 0xFF
        val cmdId = buf.short.toInt() and 0xFFFF

        val paramsLen = (dataLen - DATA_HEAD).coerceAtLeast(0)
        val params = ByteArray(paramsLen)
        if (paramsLen > 0) buf.get(params)

        val receivedCrc = buf.short.toInt() and 0xFFFF
        val calcCrc = crc16(raw, 0, FIXED_HEADER + dataLen)

        return PmeFrame(
            header1 = h1, header2 = h2, dataLen = dataLen,
            srcAddr = src, dstAddr = dst, seqNo = seqNo, status = status,
            productId = prodId, protocolType = proto, cmdId = cmdId,
            params = params, crc = receivedCrc, crcValid = (calcCrc == receivedCrc)
        )
    }

    /** 组装 0x2000 病人信息参数（32 字节） */
    fun buildPatientInfoParams(info: PmePatientInfo): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(fixedString(info.patientNo, 12))
        buf.put(info.sex.toByte())
        buf.put(info.type.toByte())
        buf.putShort(info.heightCm.toShort())
        buf.putShort(info.weightKg.toShort())
        buf.putShort(info.age.toShort())
        buf.put(fixedString(info.dataId, 12))
        return buf.array()
    }

    fun parsePatientInfo(frame: PmeFrame): PmePatientInfo? {
        if (frame.cmdId != CMD_PATIENT_INFO || !frame.crcValid) return null
        if (frame.params.size < 32) {
            Log.w(TAG, "病人信息帧太短: ${frame.params.size}")
            return null
        }
        val buf = ByteBuffer.wrap(frame.params).order(ByteOrder.LITTLE_ENDIAN)
        val no = ByteArray(12).also { buf.get(it) }
        val sex = buf.get().toInt() and 0xFF
        val type = buf.get().toInt() and 0xFF
        val height = buf.short.toInt() and 0xFFFF
        val weight = buf.short.toInt() and 0xFFFF
        val age = buf.short.toInt() and 0xFFFF
        val dataId = ByteArray(12).also { buf.get(it) }
        return PmePatientInfo(
            patientNo = trimFixed(no),
            sex = sex,
            type = type,
            heightCm = height,
            weightKg = weight,
            age = age,
            dataId = trimFixed(dataId)
        )
    }

    fun parseDeviceInfo(frame: PmeFrame): PmeDeviceInfo? {
        if (frame.cmdId != CMD_DEVICE_INFO || !frame.crcValid) return null
        val raw = frame.params.copyOf()
        return if (raw.size >= 40) {
            val serial = trimFixed(raw.copyOfRange(0, 16))
            val hw = trimFixed(raw.copyOfRange(16, 22))
            val sw = trimFixed(raw.copyOfRange(22, 40))
            PmeDeviceInfo(serialNo = serial, hardwareVersion = hw, softwareVersion = sw, raw = raw)
        } else {
            // 短载荷：整段当序列号，便于联调
            val text = trimFixed(raw)
            PmeDeviceInfo(serialNo = text, hardwareVersion = "", softwareVersion = "", raw = raw)
        }
    }

    fun parseDeviceStatus(frame: PmeFrame): PmeDeviceStatus? {
        if (frame.cmdId != CMD_DEVICE_STATUS || !frame.crcValid) return null
        val raw = frame.params.copyOf()
        if (raw.isEmpty()) {
            return PmeDeviceStatus(batteryState = null, btState = null, raw = raw)
        }
        val battery = raw[0].toInt() and 0xFF
        val bt = if (raw.size >= 2) raw[1].toInt() and 0xFF else null
        return PmeDeviceStatus(
            batteryState = battery,
            btState = bt,
            raw = raw
        )
    }

    fun parseBleName(frame: PmeFrame): PmeBleName? {
        if (frame.cmdId != CMD_BLE_NAME || !frame.crcValid) return null
        val raw = frame.params.copyOf()
        val name = trimFixed(raw).take(26)
        return PmeBleName(name = name, raw = raw)
    }

    internal fun buildFrame(
        srcAddr: Int, dstAddr: Int, productId: Int, protocolType: Int,
        cmdId: Int, status: Int, seqNo: Int, params: ByteArray
    ): ByteArray {
        val dataLen = DATA_HEAD + params.size
        val totalLen = FIXED_HEADER + dataLen + CRC_SIZE
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(HEADER1)
        buf.put(HEADER2)
        buf.putShort(dataLen.toShort())
        buf.put(srcAddr.toByte())
        buf.put(dstAddr.toByte())
        buf.putInt(seqNo)
        buf.putShort(status.toShort())
        buf.put(productId.toByte())
        buf.put(protocolType.toByte())
        buf.putShort(cmdId.toShort())
        buf.put(params)

        val crc = crc16(buf.array(), 0, FIXED_HEADER + dataLen)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    private fun fixedString(s: String, len: Int): ByteArray {
        val out = ByteArray(len)
        val raw = s.toByteArray(Charset.forName("UTF-8"))
        System.arraycopy(raw, 0, out, 0, minOf(raw.size, len))
        return out
    }

    private fun trimFixed(bytes: ByteArray): String =
        String(bytes, Charset.forName("UTF-8")).trimEnd { it == '\u0000' || it == ' ' }
}
