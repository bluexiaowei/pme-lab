package com.digitalp.pme.sdk

/**
 * 0x1000 设备信息（从机→主机）。
 * 载荷多为可读字符串；保留 raw 便于联调对照。
 */
data class PmeDeviceInfo(
    val text: String,
    val raw: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PmeDeviceInfo) return false
        return text == other.text && raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int = 31 * text.hashCode() + raw.contentHashCode()
}

/**
 * 0x1001 设备状态（从机→主机）。
 * 常见布局：battery(1B) + btState(1B) + 其余保留。
 */
data class PmeDeviceStatus(
    /** 电量 0–100；解析失败为 null */
    val batteryPercent: Int?,
    /** 蓝牙/模块状态字节；语义依固件 */
    val btState: Int?,
    val raw: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PmeDeviceStatus) return false
        return batteryPercent == other.batteryPercent &&
            btState == other.btState &&
            raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = batteryPercent ?: 0
        result = 31 * result + (btState ?: 0)
        result = 31 * result + raw.contentHashCode()
        return result
    }
}
