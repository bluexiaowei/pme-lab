package com.digitalp.pme.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * PME 生理数据提取器 — 协议 5.5 命令 0x2001（从机→主机）。
 *
 * 字段顺序：数据ID(12) + ETCO2/FICO2/Breath/SpO2/Pulse/PEF/FEV1/FVC/
 * MEF75/50/25/MMEF/FEV1_FVC%/袖带压/舒张压/收缩压/平均压/心率/体温（各 2B LE）。
 *
 * 无效：2 字节字段为 0xFFFF（文档写 0xFF）；无更新时保留上次有效值。
 * 缩放：ETCO2/FICO2/MEF系列/FEV1_FVC%/体温 除以10；FEV1/FVC 除以100（对齐实机与比值）；其余整型原值。
 */
data class PmePhysioData(
    val dataId: String,
    val etco2: Float?,
    val insco2: Float?,
    val breathRate: Float?,
    val spo2: Float?,
    val pulseRate: Float?,
    val pef: Float?,
    val fev1: Float?,
    val fvc: Float?,
    val mef75: Float?,
    val mef50: Float?,
    val mef25: Float?,
    val mmef: Float?,
    val fev1Fvc: Float?,
    val cuffPressure: Float?,
    val diastolicBp: Float?,
    val systolicBp: Float?,
    val meanBp: Float?,
    val heartRate: Float?,
    val temperature: Float?
) {
    /** 协议：无更新时保留上次有效值 */
    fun mergePreserve(prev: PmePhysioData?): PmePhysioData {
        if (prev == null) return this
        return copy(
            dataId = dataId.ifBlank { prev.dataId },
            etco2 = etco2 ?: prev.etco2,
            insco2 = insco2 ?: prev.insco2,
            breathRate = breathRate ?: prev.breathRate,
            spo2 = spo2 ?: prev.spo2,
            pulseRate = pulseRate ?: prev.pulseRate,
            pef = pef ?: prev.pef,
            fev1 = fev1 ?: prev.fev1,
            fvc = fvc ?: prev.fvc,
            mef75 = mef75 ?: prev.mef75,
            mef50 = mef50 ?: prev.mef50,
            mef25 = mef25 ?: prev.mef25,
            mmef = mmef ?: prev.mmef,
            fev1Fvc = fev1Fvc ?: prev.fev1Fvc,
            cuffPressure = cuffPressure ?: prev.cuffPressure,
            diastolicBp = diastolicBp ?: prev.diastolicBp,
            systolicBp = systolicBp ?: prev.systolicBp,
            meanBp = meanBp ?: prev.meanBp,
            heartRate = heartRate ?: prev.heartRate,
            temperature = temperature ?: prev.temperature
        )
    }
}

object PmeDataExtractor {

    private const val INVALID = 0xFFFF
    private const val TAG = "PME_LIVE"

    fun parse(frame: PmeFrame): PmePhysioData? {
        if (frame.cmdId != PmeProtocol.CMD_PHYSIO_DATA) return null
        if (!frame.crcValid) return null
        if (frame.params.size < 50) {
            Log.w(TAG, "生理数据帧太短: ${frame.params.size} 字节，期望至少 50 字节")
            return null
        }

        val buf = ByteBuffer.wrap(frame.params).order(ByteOrder.LITTLE_ENDIAN)

        val idBytes = ByteArray(12)
        buf.get(idBytes)
        val dataId = String(idBytes).trimEnd { it == '\u0000' || it == ' ' }

        fun readRaw(): Int = buf.short.toInt() and 0xFFFF

        fun readInt(): Float? {
            val raw = readRaw()
            return if (raw == INVALID) null else raw.toFloat()
        }

        fun readDiv10(): Float? {
            val raw = readRaw()
            return if (raw == INVALID) null else raw / 10f
        }

        fun readDiv100(): Float? {
            val raw = readRaw()
            return if (raw == INVALID) null else raw / 100f
        }

        val data = PmePhysioData(
            dataId = dataId,
            etco2 = readDiv10(),
            insco2 = readDiv10(),
            breathRate = readInt(),
            spo2 = readInt(),
            pulseRate = readInt(),
            pef = readInt(),
            fev1 = readDiv100(),
            fvc = readDiv100(),
            mef75 = readDiv10(),
            mef50 = readDiv10(),
            mef25 = readDiv10(),
            mmef = readDiv10(),
            fev1Fvc = readDiv10(),
            cuffPressure = readInt(),
            diastolicBp = readInt(),
            systolicBp = readInt(),
            meanBp = readInt(),
            heartRate = readInt(),
            temperature = readDiv10()
        )

        Log.i(
            TAG,
            "生理数据: id=$dataId " +
                "etco2=${data.etco2} fico2=${data.insco2} br=${data.breathRate} " +
                "spo2=${data.spo2} pulse=${data.pulseRate} " +
                "pef=${data.pef} fev1=${data.fev1} fvc=${data.fvc} " +
                "mef75=${data.mef75} mef50=${data.mef50} mef25=${data.mef25} mmef=${data.mmef} " +
                "fev1fvc=${data.fev1Fvc} " +
                "cuff=${data.cuffPressure} dbp=${data.diastolicBp} sbp=${data.systolicBp} " +
                "mbp=${data.meanBp} hr=${data.heartRate} temp=${data.temperature}"
        )

        return data
    }
}
