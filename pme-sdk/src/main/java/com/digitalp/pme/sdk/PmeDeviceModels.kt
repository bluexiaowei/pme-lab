package com.digitalp.pme.sdk

/**
 * 0x1000 设备信息（从机→主机）。
 * 布局：序列号(16) + 硬件版本(6) + 软件版本(18)。
 */
data class PmeDeviceInfo(
    val serialNo: String,
    val hardwareVersion: String,
    val softwareVersion: String,
    val raw: ByteArray
) {
    /** 便于日志的摘要 */
    val summary: String
        get() = listOf(serialNo, hardwareVersion, softwareVersion)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
            .ifBlank { "(空)" }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PmeDeviceInfo) return false
        return serialNo == other.serialNo &&
            hardwareVersion == other.hardwareVersion &&
            softwareVersion == other.softwareVersion &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = serialNo.hashCode()
        result = 31 * result + hardwareVersion.hashCode()
        result = 31 * result + softwareVersion.hashCode()
        result = 31 * result + raw.contentHashCode()
        return result
    }
}

/**
 * 0x1001 设备状态（从机→主机）。
 * 电池为档位/充电枚举，不是 0–100 百分比。
 */
data class PmeDeviceStatus(
    /** 电池状态原始字节，见 [batteryLabel] */
    val batteryState: Int?,
    /** 蓝牙连接：0 已连接，1 未连接 */
    val btState: Int?,
    val raw: ByteArray
) {
    val batteryLabel: String
        get() = batteryState?.let { describeBattery(it) } ?: "—"

    val btLabel: String
        get() = when (btState) {
            0 -> "已连接"
            1 -> "未连接"
            null -> "—"
            else -> "未知($btState)"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PmeDeviceStatus) return false
        return batteryState == other.batteryState &&
            btState == other.btState &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = batteryState ?: 0
        result = 31 * result + (btState ?: 0)
        result = 31 * result + raw.contentHashCode()
        return result
    }

    companion object {
        fun describeBattery(code: Int): String = when (code) {
            in 0..4 -> "档位${4 - code}"
            in 5..9 -> "充电${code - 5}"
            10 -> "充满"
            in 11..15 -> "充故障${code - 11}"
            16 -> "AC故障"
            else -> "码$code"
        }
    }
}

/** 0x1100 蓝牙广播名称 */
data class PmeBleName(
    val name: String,
    val raw: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PmeBleName) return false
        return name == other.name && raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + raw.contentHashCode()
}
