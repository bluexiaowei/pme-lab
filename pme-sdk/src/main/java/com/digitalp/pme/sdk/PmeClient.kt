package com.digitalp.pme.sdk

import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * PME 呼吸监测设备 SDK 入口。
 *
 * 用法：
 * ```
 * val client = PmeClient(context)
 * client.onPhysioData = { data -> ... }
 * client.scanner.startScan(targetNames = listOf("PME"), ...)
 * client.connect(device, patientInfo)
 * ```
 */
class PmeClient(context: Context) {

    private val appContext = context.applicationContext
    private val gattManager = BluetoothGattManager(appContext)

    /** BLE 扫描器 */
    val scanner = BluetoothScanner(appContext)

    var onConnectionStateChange: ((Int) -> Unit)?
        get() = gattManager.onConnectionStateChange
        set(value) { gattManager.onConnectionStateChange = value }

    var onPhysioData: ((PmePhysioData) -> Unit)?
        get() = gattManager.onPhysioData
        set(value) { gattManager.onPhysioData = value }

    var onPatientInfo: ((PmePatientInfo) -> Unit)?
        get() = gattManager.onPatientInfo
        set(value) { gattManager.onPatientInfo = value }

    var onDeviceInfo: ((PmeDeviceInfo) -> Unit)?
        get() = gattManager.onDeviceInfo
        set(value) { gattManager.onDeviceInfo = value }

    var onDeviceStatus: ((PmeDeviceStatus) -> Unit)?
        get() = gattManager.onDeviceStatus
        set(value) { gattManager.onDeviceStatus = value }

    var onBleName: ((PmeBleName) -> Unit)?
        get() = gattManager.onBleName
        set(value) { gattManager.onBleName = value }

    var onLog: ((String) -> Unit)?
        get() = gattManager.onLog
        set(value) { gattManager.onLog = value }

    /** 原始收发字节回调，direction 为 "TX" / "RX" */
    var onRawFrame: ((direction: String, bytes: ByteArray) -> Unit)?
        get() = gattManager.onRawFrame
        set(value) { gattManager.onRawFrame = value }

    /**
     * 连接设备：自动 CCCD 订阅 → 0x8001 建链 → 周期 0x8002 → 收 0x2001 等。
     * @param patientInfo 可选；仅当需要实验性主机写 0x2000 时传入（协议原文为从机→主机）。
     */
    fun connect(device: BluetoothDevice, patientInfo: PmePatientInfo? = null) {
        val params = patientInfo?.let { PmeProtocol.buildPatientInfoParams(it) }
        gattManager.connect(device, params)
    }

    fun connect(device: BluetoothDevice, patientParams: ByteArray?) {
        gattManager.connect(device, patientParams)
    }

    fun disconnect() = gattManager.disconnect(closeImmediately = false)

    fun sendPatientInfo(info: PmePatientInfo) {
        gattManager.sendPatientInfo(PmeProtocol.buildPatientInfoParams(info))
    }

    fun sendRequest(cmdId: Int, params: ByteArray = ByteArray(0)) {
        gattManager.sendRequest(cmdId, params)
    }

    companion object {
        const val STATE_DISCONNECTED = BluetoothGattManager.STATE_DISCONNECTED
        const val STATE_CONNECTING = BluetoothGattManager.STATE_CONNECTING
        const val STATE_CONNECTED = BluetoothGattManager.STATE_CONNECTED
        const val STATE_DISCOVERING = BluetoothGattManager.STATE_DISCOVERING
        const val STATE_READY = BluetoothGattManager.STATE_READY
    }
}
