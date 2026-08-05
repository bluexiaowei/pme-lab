package com.digitalp.pme.sdk

import android.bluetooth.BluetoothDevice

/**
 * BLE 扫描结果
 */
data class BleScanResult(
    val name: String,
    val address: String,
    val rssi: Int,
    /** 扫描时拿到的设备句柄，连接时应优先使用，避免再 getRemoteDevice */
    val device: BluetoothDevice? = null
)
