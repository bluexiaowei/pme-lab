package com.digitalp.pme.sdk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.util.size

/**
 * BLE 蓝牙扫描管理器
 */
class BluetoothScanner(private val context: Context) {

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var activeScanCallback: ScanCallback? = null
    private var scanStoppedListener: (() -> Unit)? = null

    /** 蓝牙是否开启 */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /** BLE 是否可用 */
    fun isBleSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /**
     * 检查是否已授予蓝牙权限
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                @Suppress("DEPRECATION")
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 开始 BLE 扫描
     * @param targetNames 目标设备名称关键词列表，空表示扫描所有
     * @param onDeviceFound 发现/更新设备回调（同一地址可能多次，用于刷新 RSSI）
     * @param onScanFailed 扫描失败回调 (errorCode)
     * @param onScanStopped 扫描停止回调（主动 stop 或失败后）
     * @param verboseLog 是否输出完整广播日志（联调可开，正式接入建议关）
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        targetNames: List<String> = emptyList(),
        onDeviceFound: (BleScanResult) -> Unit,
        onScanFailed: (Int) -> Unit,
        onScanStopped: () -> Unit = {},
        verboseLog: Boolean = false
    ): Boolean {
        if (!hasBluetoothPermissions()) {
            onScanFailed(SCAN_FAILED_PERMISSION_DENIED)
            return false
        }
        if (!isBleSupported()) {
            onScanFailed(SCAN_FAILED_BLE_UNSUPPORTED)
            return false
        }
        if (!isBluetoothEnabled()) {
            onScanFailed(SCAN_FAILED_BT_DISABLED)
            return false
        }

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            onScanFailed(SCAN_FAILED_INTERNAL_ERROR)
            return false
        }

        if (isScanning) {
            stopScan()
        }

        isScanning = true
        scanStoppedListener = onScanStopped
        val seenDevices = mutableSetOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitResult(result, targetNames, seenDevices, onDeviceFound, verboseLog, callbackType)
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                activeScanCallback = null
                onScanFailed(errorCode)
                notifyStopped()
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { result ->
                    emitResult(result, targetNames, seenDevices, onDeviceFound, verboseLog = false)
                }
            }
        }
        activeScanCallback = callback

        try {
            bleScanner?.startScan(callback)
        } catch (_: SecurityException) {
            isScanning = false
            activeScanCallback = null
            onScanFailed(SCAN_FAILED_PERMISSION_DENIED)
            notifyStopped()
            return false
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun emitResult(
        result: ScanResult,
        targetNames: List<String>,
        seenDevices: MutableSet<String>,
        onDeviceFound: (BleScanResult) -> Unit,
        verboseLog: Boolean,
        callbackType: Int = -1
    ) {
        val device = result.device
        val address = device.address ?: return
        val rssi = result.rssi
        val scanRecord = result.scanRecord
        // 优先广播名：Android 上 device.name 经常为 null
        val name = scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            ?: device.name?.takeIf { it.isNotBlank() }
            ?: "未知设备"

        if (targetNames.isNotEmpty()) {
            val matched = targetNames.any { keyword ->
                name.contains(keyword, ignoreCase = true)
            }
            if (!matched) return
        }

        val isNew = address !in seenDevices
        if (isNew) {
            seenDevices.add(address)
        }

        if (verboseLog && isNew) {
            Log.w("BLE_SCAN", "========== 发现设备 ==========")
            Log.w("BLE_SCAN", "name        = $name")
            Log.w("BLE_SCAN", "address     = $address")
            Log.w("BLE_SCAN", "rssi        = $rssi dBm")
            if (callbackType >= 0) {
                Log.w("BLE_SCAN", "callbackType= $callbackType")
            }
            Log.w("BLE_SCAN", "device.type = ${device.type}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Log.w("BLE_SCAN", "device.alias= ${device.alias}")
            }
            Log.w("BLE_SCAN", "bondState   = ${device.bondState}")
            scanRecord?.let { record ->
                Log.w("BLE_SCAN", "deviceName  = ${record.deviceName}")
                Log.w("BLE_SCAN", "txPower     = ${record.txPowerLevel}")
                record.serviceUuids?.forEachIndexed { i, uuid ->
                    Log.w("BLE_SCAN", "serviceUuid[$i] = $uuid")
                }
                record.manufacturerSpecificData?.let { mfrData ->
                    for (i in 0 until mfrData.size) {
                        val id = mfrData.keyAt(i)
                        val data = mfrData.valueAt(i)
                        Log.w("BLE_SCAN", "manufacturerId = 0x${id.toString(16)} (${id})")
                        Log.w("BLE_SCAN", "manufacturerData = ${data.joinToString(" ") { "%02X".format(it) }}")
                    }
                }
                record.serviceData?.forEach { (uuid, data) ->
                    Log.w("BLE_SCAN", "serviceData uuid=$uuid data=${data.joinToString(" ") { "%02X".format(it) }}")
                }
                Log.w("BLE_SCAN", "rawBytes    = ${record.bytes.joinToString(" ") { "%02X".format(it) }}")
            }
            Log.w("BLE_SCAN", "================================")
        }

        onDeviceFound(
            BleScanResult(
                name = name,
                address = address,
                rssi = rssi,
                device = device
            )
        )
    }

    /**
     * 停止扫描
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = activeScanCallback
        if (cb == null) {
            if (isScanning) {
                isScanning = false
                notifyStopped()
            }
            return
        }
        try {
            bleScanner?.stopScan(cb)
        } catch (_: SecurityException) {
        }
        activeScanCallback = null
        isScanning = false
        notifyStopped()
    }

    private fun notifyStopped() {
        val listener = scanStoppedListener
        scanStoppedListener = null
        listener?.invoke()
    }

    companion object {
        const val SCAN_FAILED_PERMISSION_DENIED = 1
        const val SCAN_FAILED_BLE_UNSUPPORTED = 2
        const val SCAN_FAILED_BT_DISABLED = 3
        const val SCAN_FAILED_INTERNAL_ERROR = 4
    }
}
