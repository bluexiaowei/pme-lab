package com.digitalp.pme.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmeCodecTest {

    @Test
    fun buildRequest_roundTrip_crcValid() {
        val codec = PmeCodec()
        val frameBytes = codec.buildRequest(PmeProtocol.CMD_LINK)
        val frames = codec.feed(frameBytes)
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertTrue(frame.crcValid)
        assertEquals(PmeProtocol.CMD_LINK, frame.cmdId)
        assertEquals(PmeProtocol.STATUS_REQUEST, frame.status)
        assertEquals(0, frame.seqNo)
    }

    @Test
    fun feed_handlesSplitPackets() {
        val codec = PmeCodec()
        val full = codec.buildRequest(PmeProtocol.CMD_KEEPALIVE, byteArrayOf(0x01, 0x02))
        val mid = full.size / 2
        assertTrue(codec.feed(full.copyOfRange(0, mid)).isEmpty())
        val frames = codec.feed(full.copyOfRange(mid, full.size))
        assertEquals(1, frames.size)
        assertTrue(frames[0].crcValid)
        assertEquals(PmeProtocol.CMD_KEEPALIVE, frames[0].cmdId)
        assertEquals(2, frames[0].params.size)
    }

    @Test
    fun feed_skipsGarbageBeforeHeader() {
        val codec = PmeCodec()
        val full = codec.buildRequest(PmeProtocol.CMD_LINK)
        val withGarbage = byteArrayOf(0x00, 0x11, 0x22) + full
        val frames = codec.feed(withGarbage)
        assertEquals(1, frames.size)
        assertTrue(frames[0].crcValid)
    }

    @Test
    fun buildAck_swapsAddressesAndEchoesSeq() {
        val codec = PmeCodec()
        val req = codec.buildRequest(PmeProtocol.CMD_PHYSIO_DATA)
        val reqFrame = codec.feed(req).single()
        val ackBytes = codec.buildAck(reqFrame)
        // 用新 codec 解析，避免缓冲残留
        val ackFrame = PmeCodec().feed(ackBytes).single()
        assertTrue(ackFrame.crcValid)
        assertEquals(PmeProtocol.STATUS_RESPONSE, ackFrame.status)
        assertEquals(reqFrame.cmdId, ackFrame.cmdId)
        assertEquals(reqFrame.srcAddr, ackFrame.dstAddr)
        assertEquals(reqFrame.dstAddr, ackFrame.srcAddr)
        val echoedSeq = ByteBuffer.wrap(ackFrame.params).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(reqFrame.seqNo, echoedSeq)
    }

    @Test
    fun patientInfo_roundTrip() {
        val info = PmePatientInfo(
            patientNo = "LAB0001",
            sex = 0,
            type = 0,
            heightCm = 170,
            weightKg = 65,
            age = 40,
            dataId = "13800138000"
        )
        val params = PmeProtocol.buildPatientInfoParams(info)
        assertEquals(32, params.size)
        val codec = PmeCodec()
        val frameBytes = codec.buildRequest(PmeProtocol.CMD_PATIENT_INFO, params)
        val frame = codec.feed(frameBytes).single()
        val parsed = PmeProtocol.parsePatientInfo(frame)
        assertNotNull(parsed)
        assertEquals("LAB0001", parsed!!.patientNo)
        assertEquals(170, parsed.heightCm)
        assertEquals(65, parsed.weightKg)
        assertEquals("13800138000", parsed.dataId)
    }

    @Test
    fun twoCodecs_haveIndependentSeqAndBuffers() {
        val a = PmeCodec()
        val b = PmeCodec()
        val fa = a.feed(a.buildRequest(PmeProtocol.CMD_LINK)).single()
        val fb = b.feed(b.buildRequest(PmeProtocol.CMD_LINK)).single()
        assertEquals(0, fa.seqNo)
        assertEquals(0, fb.seqNo)
        val half = a.buildRequest(PmeProtocol.CMD_KEEPALIVE)
        a.feed(half.copyOfRange(0, 4))
        // b 不应受 a 半包影响
        assertEquals(1, b.feed(b.buildRequest(PmeProtocol.CMD_KEEPALIVE)).size)
    }
}

class PmeDataExtractorTest {

    @Test
    fun parse_scalesAndTreatsFFFFAsNull() {
        val params = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ID123".toByteArray())
            while (position() < 12) put(0)
            putShort(125)   // etco2 /10 -> 12.5
            putShort(0xFFFF.toShort()) // fico2 invalid
            putShort(18)    // breath
            putShort(98)    // spo2
            putShort(72)    // pulse
            putShort(400)   // pef
            putShort(350)   // fev1 /100 -> 3.5
            putShort(420)   // fvc /100 -> 4.2
            putShort(50)    // mef75 /10
            putShort(40)
            putShort(30)
            putShort(35)
            putShort(833)   // fev1/fvc /10
            putShort(120)   // cuff
            putShort(80)    // dbp
            putShort(120)   // sbp
            putShort(93)    // mbp
            putShort(70)    // hr
            putShort(365)   // temp /10 -> 36.5
        }.array()

        val codec = PmeCodec()
        val frame = codec.feed(codec.buildRequest(PmeProtocol.CMD_PHYSIO_DATA, params)).single()
        // buildRequest 用 STATUS_REQUEST，extractor 只看 cmdId；改用解析后的帧即可
        val data = PmeDataExtractor.parse(frame)
        assertNotNull(data)
        assertEquals("ID123", data!!.dataId)
        assertEquals(12.5f, data.etco2)
        assertNull(data.insco2)
        assertEquals(98f, data.spo2)
        assertEquals(3.5f, data.fev1)
        assertEquals(4.2f, data.fvc)
        assertEquals(36.5f, data.temperature)
    }

    @Test
    fun mergePreserve_keepsPreviousValid() {
        val prev = PmePhysioData(
            dataId = "A", etco2 = 10f, insco2 = null, breathRate = 12f,
            spo2 = 99f, pulseRate = null, pef = null, fev1 = null, fvc = null,
            mef75 = null, mef50 = null, mef25 = null, mmef = null, fev1Fvc = null,
            cuffPressure = null, diastolicBp = null, systolicBp = null, meanBp = null,
            heartRate = null, temperature = 36f
        )
        val next = PmePhysioData(
            dataId = "", etco2 = null, insco2 = 1f, breathRate = 14f,
            spo2 = null, pulseRate = 70f, pef = null, fev1 = null, fvc = null,
            mef75 = null, mef50 = null, mef25 = null, mmef = null, fev1Fvc = null,
            cuffPressure = null, diastolicBp = null, systolicBp = null, meanBp = null,
            heartRate = null, temperature = null
        )
        val merged = next.mergePreserve(prev)
        assertEquals("A", merged.dataId)
        assertEquals(10f, merged.etco2)
        assertEquals(1f, merged.insco2)
        assertEquals(14f, merged.breathRate)
        assertEquals(99f, merged.spo2)
        assertEquals(70f, merged.pulseRate)
        assertEquals(36f, merged.temperature)
    }

    @Test
    fun parse_rejectsShortPayload() {
        val codec = PmeCodec()
        val frame = codec.feed(codec.buildRequest(PmeProtocol.CMD_PHYSIO_DATA, ByteArray(10))).single()
        assertNull(PmeDataExtractor.parse(frame))
    }

    @Test
    fun parse_rejectsWrongCmd() {
        val codec = PmeCodec()
        val frame = codec.feed(codec.buildRequest(PmeProtocol.CMD_LINK)).single()
        assertNull(PmeDataExtractor.parse(frame))
    }

    @Test
    fun parse_rejectsBadCrc() {
        val codec = PmeCodec()
        val bytes = codec.buildRequest(PmeProtocol.CMD_PHYSIO_DATA, ByteArray(50)).clone()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex] + 1).toByte()
        val frame = PmeCodec().feed(bytes).single()
        assertFalse(frame.crcValid)
        assertNull(PmeDataExtractor.parse(frame))
    }
}

class PmeDeviceParseTest {

    @Test
    fun parseDeviceInfo_readsUtf8Text() {
        val codec = PmeCodec()
        val payload = "PME-V1.2\u0000".toByteArray()
        val frame = codec.feed(codec.buildRequest(PmeProtocol.CMD_DEVICE_INFO, payload)).single()
        val info = PmeProtocol.parseDeviceInfo(frame)
        assertNotNull(info)
        assertEquals("PME-V1.2", info!!.text)
    }

    @Test
    fun parseDeviceStatus_readsBatteryAndBt() {
        val codec = PmeCodec()
        val frame = codec.feed(
            codec.buildRequest(PmeProtocol.CMD_DEVICE_STATUS, byteArrayOf(85, 0x01, 0x00))
        ).single()
        val status = PmeProtocol.parseDeviceStatus(frame)
        assertNotNull(status)
        assertEquals(85, status!!.batteryPercent)
        assertEquals(0x01, status.btState)
        assertEquals(3, status.raw.size)
    }

    @Test
    fun parseDeviceStatus_handlesSingleByte() {
        val codec = PmeCodec()
        val frame = codec.feed(
            codec.buildRequest(PmeProtocol.CMD_DEVICE_STATUS, byteArrayOf(42))
        ).single()
        val status = PmeProtocol.parseDeviceStatus(frame)!!
        assertEquals(42, status.batteryPercent)
        assertNull(status.btState)
    }
}
