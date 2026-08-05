package com.abysmus.pmelab

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digitalp.pme.sdk.BleScanResult
import com.digitalp.pme.sdk.BluetoothScanner
import com.digitalp.pme.sdk.PmeClient
import com.digitalp.pme.sdk.PmeDeviceInfo
import com.digitalp.pme.sdk.PmeDeviceStatus
import com.digitalp.pme.sdk.PmePatientInfo
import com.digitalp.pme.sdk.PmePhysioData
import com.digitalp.pme.sdk.PmeProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PatientDraft(
    val patientNo: String = "LAB0001",
    val sex: Int = 0,
    val type: Int = 0,
    val heightCm: String = "170",
    val weightKg: String = "65",
    val age: String = "40",
    val dataId: String = "LAB0001",
    /** 建链时是否自动下发 0x2000 */
    val sendOnConnect: Boolean = true
) {
    fun toPatientInfoOrNull(): PmePatientInfo? {
        val h = heightCm.toIntOrNull() ?: return null
        val w = weightKg.toIntOrNull() ?: return null
        val a = age.toIntOrNull() ?: return null
        if (patientNo.isBlank()) return null
        return PmePatientInfo(
            patientNo = patientNo.trim(),
            sex = sex.coerceIn(0, 1),
            type = type.coerceIn(0, 2),
            heightCm = h,
            weightKg = w,
            age = a,
            dataId = dataId.trim()
        )
    }
}

data class LabUiState(
    val permissionGranted: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<BleScanResult> = emptyList(),
    val connectionLabel: String = "未连接",
    val connectedAddress: String? = null,
    /** 通道就绪（可发令） */
    val ready: Boolean = false,
    val physio: PmePhysioData? = null,
    val deviceInfo: PmeDeviceInfo? = null,
    val deviceStatus: PmeDeviceStatus? = null,
    val recvCount: Int = 0,
    val logs: List<String> = emptyList(),
    val filterName: String = "PME",
    val statusHint: String = "申请蓝牙权限后开始扫描",
    val patient: PatientDraft = PatientDraft(),
    val cmdIdHex: String = "1000",
    val cmdParamsHex: String = "",
    val cmdError: String? = null
)

class LabViewModel(application: Application) : AndroidViewModel(application) {

    private val client = PmeClient(application)
    private val _ui = MutableStateFlow(LabUiState())
    val ui: StateFlow<LabUiState> = _ui.asStateFlow()

    private var scanTimeoutJob: Job? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        client.onLog = { msg -> appendLog(msg) }
        client.onRawFrame = { direction, bytes ->
            if (!(direction == "TX" && isKeepaliveFrame(bytes))) {
                val preview = bytes.take(24).joinToString(" ") { "%02X".format(it) }
                val more = if (bytes.size > 24) " …(+${bytes.size - 24})" else ""
                appendLog("$direction ${bytes.size}B: $preview$more")
            }
        }
        client.onPhysioData = { data ->
            val merged = data.mergePreserve(_ui.value.physio)
            _ui.update {
                it.copy(physio = merged, recvCount = it.recvCount + 1)
            }
        }
        client.onPatientInfo = { info ->
            appendLog("病人信息: ${info.patientNo} / ${info.dataId}")
        }
        client.onDeviceInfo = { info ->
            _ui.update { it.copy(deviceInfo = info) }
            appendLog("设备信息: ${info.text.ifBlank { "(空)" }}")
        }
        client.onDeviceStatus = { status ->
            _ui.update { it.copy(deviceStatus = status) }
            appendLog("设备状态: 电量=${status.batteryPercent} bt=${status.btState}")
        }
        client.onConnectionStateChange = { state ->
            val label = when (state) {
                PmeClient.STATE_DISCONNECTED -> "已断开"
                PmeClient.STATE_CONNECTING -> "连接中…"
                PmeClient.STATE_CONNECTED -> "已连接"
                PmeClient.STATE_DISCOVERING -> "发现服务…"
                PmeClient.STATE_READY -> "就绪（收发中）"
                else -> "状态 $state"
            }
            _ui.update {
                it.copy(
                    connectionLabel = label,
                    ready = state == PmeClient.STATE_READY,
                    connectedAddress = if (state == PmeClient.STATE_DISCONNECTED) null else it.connectedAddress,
                    deviceInfo = if (state == PmeClient.STATE_DISCONNECTED) null else it.deviceInfo,
                    deviceStatus = if (state == PmeClient.STATE_DISCONNECTED) null else it.deviceStatus
                )
            }
            appendLog("连接状态 → $label")
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _ui.update {
            it.copy(
                permissionGranted = granted,
                statusHint = if (granted) "可以开始扫描 PME 设备" else "需要蓝牙权限才能联调"
            )
        }
    }

    fun setFilterName(name: String) {
        _ui.update { it.copy(filterName = name) }
    }

    fun updatePatient(transform: (PatientDraft) -> PatientDraft) {
        _ui.update { it.copy(patient = transform(it.patient), cmdError = null) }
    }

    fun setCmdIdHex(value: String) {
        _ui.update { it.copy(cmdIdHex = value.filter { c -> c.isLetterOrDigit() }.take(4), cmdError = null) }
    }

    fun setCmdParamsHex(value: String) {
        _ui.update { it.copy(cmdParamsHex = value, cmdError = null) }
    }

    fun startScan() {
        if (!_ui.value.permissionGranted) {
            appendLog("权限未授予，无法扫描")
            return
        }
        if (!client.scanner.isBluetoothEnabled()) {
            appendLog("请先打开系统蓝牙")
            _ui.update { it.copy(statusHint = "蓝牙未开启") }
            return
        }

        stopScan()
        _ui.update {
            it.copy(
                scanning = true,
                devices = emptyList(),
                statusHint = "扫描中…"
            )
        }
        appendLog("开始扫描 filter=${_ui.value.filterName.ifBlank { "(全部)" }}")

        val filter = _ui.value.filterName.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(it) }
            ?: emptyList()

        val ok = client.scanner.startScan(
            targetNames = filter,
            onDeviceFound = { device ->
                val isNew = _ui.value.devices.none { it.address == device.address }
                _ui.update { state ->
                    val idx = state.devices.indexOfFirst { it.address == device.address }
                    if (idx < 0) {
                        state.copy(devices = (state.devices + device).sortedByDescending { it.rssi })
                    } else {
                        val updated = state.devices.toMutableList()
                        updated[idx] = device
                        state.copy(devices = updated.sortedByDescending { it.rssi })
                    }
                }
                if (isNew) {
                    appendLog("发现 ${device.name} [${device.address}] rssi=${device.rssi}")
                }
            },
            onScanFailed = { code ->
                val reason = when (code) {
                    BluetoothScanner.SCAN_FAILED_PERMISSION_DENIED -> "权限不足"
                    BluetoothScanner.SCAN_FAILED_BLE_UNSUPPORTED -> "不支持 BLE"
                    BluetoothScanner.SCAN_FAILED_BT_DISABLED -> "蓝牙未开启"
                    else -> "错误码 $code"
                }
                _ui.update { it.copy(scanning = false, statusHint = "扫描失败: $reason") }
                appendLog("扫描失败: $reason")
            },
            onScanStopped = {
                _ui.update { it.copy(scanning = false) }
            },
            verboseLog = true
        )

        if (!ok) {
            _ui.update { it.copy(scanning = false, statusHint = "无法启动扫描") }
            return
        }

        scanTimeoutJob = viewModelScope.launch {
            delay(15_000)
            if (_ui.value.scanning) {
                stopScan()
                _ui.update {
                    it.copy(
                        statusHint = if (it.devices.isEmpty()) "未发现设备，可调整名称过滤后重试" else "扫描结束"
                    )
                }
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        client.scanner.stopScan()
        _ui.update { it.copy(scanning = false) }
    }

    @Suppress("MissingPermission")
    fun connect(address: String) {
        stopScan()
        val scanned = _ui.value.devices.firstOrNull { it.address == address }
        val device: BluetoothDevice = scanned?.device ?: run {
            val manager = getApplication<Application>()
                .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            if (adapter == null) {
                appendLog("无蓝牙适配器")
                return
            }
            try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                appendLog("无效地址: $address")
                return
            }
        }

        val draft = _ui.value.patient
        val patient = if (draft.sendOnConnect) {
            draft.toPatientInfoOrNull().also {
                if (it == null) appendLog("病人信息无效，将不带 0x2000 建链")
            }
        } else {
            null
        }

        _ui.update {
            it.copy(
                connectedAddress = address,
                physio = null,
                deviceInfo = null,
                deviceStatus = null,
                recvCount = 0,
                ready = false,
                connectionLabel = "连接中…",
                statusHint = "正在连接 $address"
            )
        }
        appendLog("连接 ${scanned?.name ?: address} [$address]")
        client.connect(device, patient)
    }

    fun disconnect() {
        client.disconnect()
        _ui.update {
            it.copy(
                connectionLabel = "已断开",
                connectedAddress = null,
                ready = false,
                statusHint = "已断开连接"
            )
        }
    }

    fun sendPatientInfo() {
        if (!_ui.value.ready) {
            _ui.update { it.copy(cmdError = "通道未就绪") }
            return
        }
        val info = _ui.value.patient.toPatientInfoOrNull()
        if (info == null) {
            _ui.update { it.copy(cmdError = "病人信息填写有误") }
            return
        }
        client.sendPatientInfo(info)
        appendLog("下发病人信息 0x2000: ${info.patientNo}")
        _ui.update { it.copy(statusHint = "已下发病人信息", cmdError = null) }
    }

    fun sendPreset(cmdId: Int) {
        setCmdIdHex("%04X".format(cmdId))
        sendCustomCommand(cmdId, ByteArray(0))
    }

    fun sendCustomCommand() {
        val cmdId = parseCmdId(_ui.value.cmdIdHex)
        if (cmdId == null) {
            _ui.update { it.copy(cmdError = "cmdId 需为 1–4 位十六进制") }
            return
        }
        val params = parseHexParams(_ui.value.cmdParamsHex)
        if (params == null) {
            _ui.update { it.copy(cmdError = "参数 hex 格式错误（如 AA BB 或 AABB）") }
            return
        }
        sendCustomCommand(cmdId, params)
    }

    private fun sendCustomCommand(cmdId: Int, params: ByteArray) {
        if (!_ui.value.ready) {
            _ui.update { it.copy(cmdError = "通道未就绪，请先连接") }
            return
        }
        client.sendRequest(cmdId, params)
        appendLog("发令 cmd=0x${"%04X".format(cmdId)} params=${params.size}B")
        _ui.update { it.copy(statusHint = "已发送 0x${"%04X".format(cmdId)}", cmdError = null) }
    }

    fun clearLogs() {
        _ui.update { it.copy(logs = emptyList()) }
    }

    private fun parseCmdId(hex: String): Int? {
        val s = hex.trim()
        if (s.isEmpty()) return null
        return s.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
    }

    private fun parseHexParams(text: String): ByteArray? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return ByteArray(0)
        val hex = cleaned.replace(Regex("[\\s,;]+"), "")
        if (hex.length % 2 != 0) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun appendLog(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        Log.i(TAG, msg)
        _ui.update { state ->
            state.copy(logs = (listOf(line) + state.logs).take(MAX_LOGS))
        }
    }

    private fun isKeepaliveFrame(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        val cmdId = (bytes[14].toInt() and 0xFF) or ((bytes[15].toInt() and 0xFF) shl 8)
        return cmdId == PmeProtocol.CMD_KEEPALIVE
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        client.disconnect()
    }

    companion object {
        private const val TAG = "PME_LAB"
        private const val MAX_LOGS = 200
    }
}
