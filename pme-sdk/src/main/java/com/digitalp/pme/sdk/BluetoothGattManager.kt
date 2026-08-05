package com.digitalp.pme.sdk

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BLE GATT 连接管理器
 *
 * 流程：connect → discover → 订阅 Notify → 0x8001 建链 → 0x2000 病人信息 → 周期 0x8002 → 收 0x2001
 */
class BluetoothGattManager(private val context: Context) {

    companion object {
        private const val TAG = "BLE_GATT"
        private const val TAG_DATA = "PME_LIVE"

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_DISCOVERING = 3
        const val STATE_READY = 4

        private const val KEEPALIVE_INTERVAL_MS = 2000L

        val PME_SERVICE: UUID = UUID.fromString("69400001-b5a3-f393-e0a9-e50e24dcca99")
        val PME_NOTIFY: UUID = UUID.fromString("69400002-b5a3-f393-e0a9-e50e24dcca99")
        val PME_WRITE: UUID = UUID.fromString("69400003-b5a3-f393-e0a9-e50e24dcca99")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val codec = PmeCodec()
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var pendingDevice: BluetoothDevice? = null
    private var retryCount = 0
    private val MAX_RETRY = 1
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 主动断开时先 disconnect，等回调再 close，避免竞态 */
    private var intentionalClose = false
    /** closeImmediately 路径已通知过断开，忽略随后的 STATE_DISCONNECTED 回调 */
    private var suppressDisconnectCallback = false

    /** 建链后待发送的 0x2000 参数（可为 null） */
    private var pendingPatientParams: ByteArray? = null
    private var linkSent = false
    private var patientSent = false
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var writing = false
    private var keepaliveRunning = false
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (!keepaliveRunning || writeCharacteristic == null) return
            sendRequest(PmeProtocol.CMD_KEEPALIVE)
            mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    var onConnectionStateChange: ((state: Int) -> Unit)? = null
    var onPhysioData: ((PmePhysioData) -> Unit)? = null
    var onPatientInfo: ((PmePatientInfo) -> Unit)? = null
    var onDeviceInfo: ((PmeDeviceInfo) -> Unit)? = null
    var onDeviceStatus: ((PmeDeviceStatus) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    /** 原始收发帧（hex 已格式化前的字节），direction: "TX" / "RX" */
    var onRawFrame: ((direction: String, bytes: ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, patientParams: ByteArray? = null) {
        disconnect(closeImmediately = true)
        cancelDiscovery()
        pendingDevice = device
        pendingPatientParams = patientParams
        retryCount = 0
        linkSent = false
        patientSent = false
        intentionalClose = false
        suppressDisconnectCallback = false
        codec.clearBuffer()
        doConnect(device)
    }

    @SuppressLint("MissingPermission")
    private fun cancelDiscovery() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.cancelDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(device: BluetoothDevice) {
        onConnectionStateChange?.invoke(STATE_CONNECTING)
        Log.i(TAG, "连接设备: ${device.address} (${device.name}) 第${retryCount + 1}次")

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
    }

    /**
     * @param closeImmediately 为 true 时立刻 close（用于重新连接前清理）；
     * 正常断开则先 disconnect，在 STATE_DISCONNECTED 回调里再 close。
     */
    @SuppressLint("MissingPermission")
    fun disconnect(closeImmediately: Boolean = false) {
        stopKeepalive()
        mainHandler.removeCallbacksAndMessages(null)
        writeQueue.clear()
        writing = false
        linkSent = false
        patientSent = false
        pendingPatientParams = null
        codec.clearBuffer()

        val gatt = bluetoothGatt
        if (gatt == null) {
            isConnected = false
            onConnectionStateChange?.invoke(STATE_DISCONNECTED)
            return
        }

        if (closeImmediately) {
            intentionalClose = true
            suppressDisconnectCallback = true
            try {
                gatt.disconnect()
            } catch (_: Exception) {
            }
            closeGatt()
            onConnectionStateChange?.invoke(STATE_DISCONNECTED)
            return
        }

        intentionalClose = true
        try {
            gatt.disconnect()
        } catch (_: Exception) {
            closeGatt()
            onConnectionStateChange?.invoke(STATE_DISCONNECTED)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }
        bluetoothGatt = null
        writeCharacteristic = null
        isConnected = false
        intentionalClose = false
    }

    fun sendRequest(cmdId: Int, params: ByteArray = ByteArray(0)) {
        if (writeCharacteristic == null) {
            Log.w(TAG_DATA, "WRITE 特征未就绪，无法发送 cmdId=0x${cmdId.toString(16)}")
            return
        }
        val frame = codec.buildRequest(cmdId, params)
        if (cmdId == PmeProtocol.CMD_KEEPALIVE) {
            Log.d(TAG_DATA, "心跳 0x8002")
        } else {
            Log.i(TAG_DATA, "发送请求: cmdId=0x${cmdId.toString(16)} ${frame.size}B")
            Log.i(TAG_DATA, "TX: ${frame.joinToString(" ") { "%02X".format(it) }}")
        }
        onRawFrame?.invoke("TX", frame)
        enqueueWrite(frame)
    }

    fun sendAck(frame: PmeFrame) {
        if (writeCharacteristic == null) return
        val ack = codec.buildAck(frame)
        Log.i(TAG_DATA, "发送 ACK: cmdId=0x${frame.cmdId.toString(16)} seq=${frame.seqNo}")
        onRawFrame?.invoke("TX", ack)
        enqueueWrite(ack)
    }

    private fun enqueueWrite(bytes: ByteArray) {
        writeQueue.addLast(bytes)
        pumpWrite()
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrite() {
        if (writing) return
        val ch = writeCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        val payload = writeQueue.removeFirstOrNull() ?: return
        writing = true

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                ch,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = payload
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ch)
        }
        if (!ok) {
            Log.w(TAG_DATA, "writeCharacteristic 返回 false，稍后重试")
            writeQueue.addFirst(payload)
            writing = false
            mainHandler.postDelayed({ pumpWrite() }, 80)
            return
        }
        // WRITE_NO_RESPONSE 常无回调，短延时后继续队列
        mainHandler.postDelayed({
            writing = false
            pumpWrite()
        }, 40)
    }

    fun sendPatientInfo(params: ByteArray) {
        pendingPatientParams = params
        if (writeCharacteristic == null || !linkSent) return
        // 允许建链后重复下发（联调改病人信息）
        patientSent = true
        Log.i(TAG_DATA, "发送病人信息 0x2000 (${params.size}B)")
        sendRequest(PmeProtocol.CMD_PATIENT_INFO, params)
    }

    private fun doSendPatientInfo() {
        val params = pendingPatientParams ?: return
        if (patientSent) return
        patientSent = true
        Log.i(TAG_DATA, "发送病人信息 0x2000 (${params.size}B)")
        sendRequest(PmeProtocol.CMD_PATIENT_INFO, params)
    }

    private fun startLinkHandshake() {
        if (linkSent) return
        linkSent = true
        onConnectionStateChange?.invoke(STATE_READY)
        onLog?.invoke("CCCD 就绪，发送 0x8001 建链")
        Log.i(TAG_DATA, "发送 0x8001 建链请求...")
        sendRequest(PmeProtocol.CMD_LINK)

        if (pendingPatientParams != null) {
            mainHandler.postDelayed({ doSendPatientInfo() }, 300)
        }
        mainHandler.postDelayed({ startKeepalive() }, 1000)
    }

    private fun startKeepalive() {
        if (keepaliveRunning || writeCharacteristic == null) return
        keepaliveRunning = true
        Log.i(TAG_DATA, "启动 0x8002 心跳，间隔 ${KEEPALIVE_INTERVAL_MS}ms")
        mainHandler.post(keepaliveRunnable)
    }

    private fun stopKeepalive() {
        keepaliveRunning = false
        mainHandler.removeCallbacks(keepaliveRunnable)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "连接失败: status=$status, 重试=${retryCount}/${MAX_RETRY}")
                val wasIntentional = intentionalClose
                closeGatt()
                if (suppressDisconnectCallback) {
                    suppressDisconnectCallback = false
                    return
                }
                if (!wasIntentional && retryCount < MAX_RETRY) {
                    retryCount++
                    val device = pendingDevice
                    if (device != null) {
                        mainHandler.postDelayed({ doConnect(device) }, 3000)
                        return
                    }
                }
                onConnectionStateChange?.invoke(STATE_DISCONNECTED)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "已连接，开始发现服务...")
                    isConnected = true
                    onConnectionStateChange?.invoke(STATE_CONNECTED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val mtuOk = gatt.requestMtu(247)
                        if (!mtuOk) gatt.discoverServices()
                    } else {
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "已断开连接")
                    isConnected = false
                    stopKeepalive()
                    writeQueue.clear()
                    writing = false
                    closeGatt()
                    if (suppressDisconnectCallback) {
                        suppressDisconnectCallback = false
                        return
                    }
                    onConnectionStateChange?.invoke(STATE_DISCONNECTED)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU=$mtu status=$status，开始发现服务")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "服务发现失败: status=$status")
                onLog?.invoke("服务发现失败: $status")
                return
            }

            onConnectionStateChange?.invoke(STATE_DISCOVERING)

            val sb = StringBuilder()
            for (service in gatt.services) {
                Log.i(TAG, "Service: ${service.uuid}")
                sb.appendLine("--- Service: ${service.uuid} ---")
                for (ch in service.characteristics) {
                    sb.appendLine("  Char: ${ch.uuid}  props=${describeProperties(ch.properties)}")
                }
            }
            onLog?.invoke(sb.toString())

            val service = gatt.getService(PME_SERVICE)
            if (service == null) {
                Log.w(TAG, "未找到 PME 服务 $PME_SERVICE")
                onLog?.invoke("未找到 PME 服务")
                return
            }

            val notifyCh = service.getCharacteristic(PME_NOTIFY)
            val writeCh = service.getCharacteristic(PME_WRITE)
            if (notifyCh == null || writeCh == null) {
                Log.w(TAG, "PME 特征缺失 notify=$notifyCh write=$writeCh")
                onLog?.invoke("PME Notify/Write 特征缺失")
                return
            }

            writeCharacteristic = writeCh
            Log.i(TAG, "PME 通道就绪: NOTIFY=$PME_NOTIFY WRITE=$PME_WRITE")
            subscribeNotify(gatt, notifyCh)
        }

        @SuppressLint("MissingPermission")
        private fun subscribeNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val ok = gatt.setCharacteristicNotification(ch, true)
            Log.i(TAG, "setCharacteristicNotification: $ok")

            val descriptor = ch.getDescriptor(CCCD)
            if (descriptor == null) {
                Log.w(TAG, "未找到 CCCD")
                onLog?.invoke("未找到 CCCD")
                return
            }
            val writeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            Log.i(TAG, "写入 CCCD ENABLE_NOTIFICATION writeOk=$writeOk")
            onLog?.invoke("正在订阅 Notify: ${ch.uuid}")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "CCCD 写入结果: status=$status uuid=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD) {
                startLinkHandshake()
            } else {
                onLog?.invoke("CCCD 写入失败: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleNotification(bytes)
        }

        private fun handleNotification(bytes: ByteArray) {
            if (bytes.isEmpty()) {
                Log.w(TAG_DATA, "空通知")
                return
            }

            Log.i(TAG_DATA, "收到 ${bytes.size} 字节: ${bytes.joinToString(" ") { "%02X".format(it) }}")
            onRawFrame?.invoke("RX", bytes)

            val frames = codec.feed(bytes)
            Log.i(TAG_DATA, "解析出 ${frames.size} 帧")

            for (frame in frames) {
                if (!frame.crcValid) {
                    Log.w(TAG_DATA, "CRC 失败 cmdId=0x${frame.cmdId.toString(16)} seq=${frame.seqNo}")
                    continue
                }

                Log.i(
                    TAG_DATA,
                    "帧: cmdId=0x${frame.cmdId.toString(16)} status=0x${frame.status.toString(16)} " +
                        "seq=${frame.seqNo} params=${frame.params.size}B src=${frame.srcAddr} dst=${frame.dstAddr}"
                )

                val needAck = frame.status != PmeProtocol.STATUS_RESPONSE

                when (frame.cmdId) {
                    PmeProtocol.CMD_LINK, PmeProtocol.CMD_KEEPALIVE -> {
                        Log.i(TAG_DATA, "链路帧 cmd=0x${frame.cmdId.toString(16)} status=0x${frame.status.toString(16)}")
                        if (needAck) sendAck(frame)
                        if (frame.cmdId == PmeProtocol.CMD_LINK && !patientSent && pendingPatientParams != null) {
                            mainHandler.postDelayed({ doSendPatientInfo() }, 200)
                        }
                    }
                    PmeProtocol.CMD_PATIENT_INFO -> {
                        val info = PmeProtocol.parsePatientInfo(frame)
                        if (info != null) {
                            Log.i(TAG_DATA, "病人信息: no=${info.patientNo} sex=${info.sex} age=${info.age} dataId=${info.dataId}")
                            onPatientInfo?.invoke(info)
                        }
                        if (needAck) sendAck(frame)
                    }
                    PmeProtocol.CMD_PHYSIO_DATA -> {
                        val data = PmeDataExtractor.parse(frame)
                        if (data != null) {
                            onPhysioData?.invoke(data)
                        } else {
                            Log.w(TAG_DATA, "生理数据解析失败")
                        }
                        if (needAck) sendAck(frame)
                    }
                    PmeProtocol.CMD_DEVICE_INFO -> {
                        val info = PmeProtocol.parseDeviceInfo(frame)
                        if (info != null) {
                            Log.i(TAG_DATA, "设备信息: ${info.text}")
                            onDeviceInfo?.invoke(info)
                        }
                        if (needAck) sendAck(frame)
                    }
                    PmeProtocol.CMD_DEVICE_STATUS -> {
                        val statusInfo = PmeProtocol.parseDeviceStatus(frame)
                        if (statusInfo != null) {
                            Log.i(
                                TAG_DATA,
                                "设备状态: battery=${statusInfo.batteryPercent} btState=${statusInfo.btState}"
                            )
                            onDeviceStatus?.invoke(statusInfo)
                        }
                        if (needAck) sendAck(frame)
                    }
                    else -> {
                        Log.i(TAG_DATA, "其他帧 cmdId=0x${frame.cmdId.toString(16)}")
                        if (needAck) sendAck(frame)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "写入完成: ${characteristic.uuid} status=$status")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS && bytes != null) {
                Log.i(TAG_DATA, "读取 ${characteristic.uuid}: ${String(bytes).trim()}")
            }
        }
    }

    private fun describeProperties(properties: Int): String {
        val list = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) list.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list.add("WRITE_NO_RESP")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list.add("INDICATE")
        return list.joinToString("|")
    }
}
