package com.abysmus.pmelab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abysmus.pmelab.LabUiState
import com.abysmus.pmelab.LabViewModel
import com.digitalp.pme.sdk.BleScanResult
import com.digitalp.pme.sdk.PmeDeviceInfo
import com.digitalp.pme.sdk.PmeDeviceStatus
import com.digitalp.pme.sdk.PmePatientInfo
import com.digitalp.pme.sdk.PmePhysioData
import com.digitalp.pme.sdk.PmeProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(
    vm: LabViewModel,
    onRequestPermissions: () -> Unit,
    onEnsureBluetooth: () -> Unit
) {
    val state by vm.ui.collectAsState()
    var tab by remember { mutableIntStateOf(0) } // 0 scan 1 data 2 control 3 logs

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PME Lab", fontWeight = FontWeight.Bold)
                        Text(
                            state.connectionLabel + if (state.connectedAddress != null) " · ${state.connectedAddress}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                actions = {
                    if (state.connectedAddress != null) {
                        IconButton(onClick = vm::disconnect) {
                            Icon(Icons.Default.LinkOff, contentDescription = "断开")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                state.statusHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("扫描") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("数据") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("控制") })
                FilterChip(selected = tab == 3, onClick = { tab = 3 }, label = { Text("日志") })
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (tab) {
                0 -> ScanPanel(
                    filterName = state.filterName,
                    scanning = state.scanning,
                    permissionGranted = state.permissionGranted,
                    devices = state.devices,
                    onFilterChange = vm::setFilterName,
                    onScan = {
                        onEnsureBluetooth()
                        if (!state.permissionGranted) onRequestPermissions()
                        else vm.startScan()
                    },
                    onStop = vm::stopScan,
                    onConnect = {
                        tab = 1
                        vm.connect(it)
                    }
                )
                1 -> DataPanel(
                    physio = state.physio,
                    recvCount = state.recvCount,
                    deviceInfo = state.deviceInfo,
                    deviceStatus = state.deviceStatus,
                    bleName = state.bleName,
                    receivedPatient = state.receivedPatient
                )
                2 -> ControlPanel(state = state, vm = vm)
                else -> LogPanel(logs = state.logs, onClear = vm::clearLogs)
            }
        }
    }
}

@Composable
private fun ScanPanel(
    filterName: String,
    scanning: Boolean,
    permissionGranted: Boolean,
    devices: List<BleScanResult>,
    onFilterChange: (String) -> Unit,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onConnect: (String) -> Unit
) {
    OutlinedTextField(
        value = filterName,
        onValueChange = onFilterChange,
        label = { Text("名称过滤（空=全部）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                if (scanning) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.Bluetooth,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (permissionGranted) "开始扫描" else "授权并扫描")
        }
        OutlinedButton(
            onClick = onStop,
            enabled = scanning,
            modifier = Modifier.weight(1f)
        ) {
            Text("停止")
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        "设备 ${devices.size}",
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(devices, key = { it.address }) { device ->
            DeviceCard(device, onConnect = { onConnect(device.address) })
        }
        if (devices.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (scanning) "正在扫描…" else "暂无设备，点上方开始扫描",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: BleScanResult, onConnect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name.ifBlank { "未知设备" }, fontWeight = FontWeight.SemiBold)
                Text(device.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Text("${device.rssi} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataPanel(
    physio: PmePhysioData?,
    recvCount: Int,
    deviceInfo: PmeDeviceInfo?,
    deviceStatus: PmeDeviceStatus?,
    bleName: String?,
    receivedPatient: PmePatientInfo?
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            if (deviceInfo != null || deviceStatus != null || !bleName.isNullOrBlank() || receivedPatient != null) {
                Text("设备", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    deviceStatus?.let {
                        MetricChip("电池", it.batteryLabel)
                        MetricChip("蓝牙", it.btLabel)
                    }
                    deviceInfo?.serialNo?.takeIf { it.isNotBlank() }?.let {
                        MetricChip("序列号", it.take(16))
                    }
                    deviceInfo?.hardwareVersion?.takeIf { it.isNotBlank() }?.let {
                        MetricChip("硬件", it)
                    }
                    deviceInfo?.softwareVersion?.takeIf { it.isNotBlank() }?.let {
                        MetricChip("软件", it)
                    }
                    bleName?.takeIf { it.isNotBlank() }?.let {
                        MetricChip("广播名", it.take(16))
                    }
                    receivedPatient?.let {
                        MetricChip("病人号", it.patientNo.ifBlank { "—" })
                        MetricChip("DataId", it.dataId.ifBlank { "—" })
                    }
                }
            }
            Text(
                "已接收 $recvCount 帧（0x2001）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (physio == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "连接设备后将显示实时生理数据",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            item {
                val fields = listOf(
                    "SpO₂ %" to format(physio.spo2),
                    "脉率 bpm" to format(physio.pulseRate),
                    "呼吸率" to format(physio.breathRate),
                    "ETCO₂" to format(physio.etco2),
                    "FiCO₂" to format(physio.insco2),
                    "收缩压 mmHg" to format(physio.systolicBp),
                    "舒张压 mmHg" to format(physio.diastolicBp),
                    "平均压 mmHg" to format(physio.meanBp),
                    "袖带压 mmHg" to format(physio.cuffPressure),
                    "心率 bpm" to format(physio.heartRate),
                    "体温 ℃" to format(physio.temperature),
                    "PEF L/min" to format(physio.pef),
                    "FEV1 L" to format(physio.fev1),
                    "FVC L" to format(physio.fvc),
                    "FEV1/FVC %" to format(physio.fev1Fvc),
                    "MEF75 L/s" to format(physio.mef75),
                    "MEF50 L/s" to format(physio.mef50),
                    "MEF25 L/s" to format(physio.mef25),
                    "MMEF L/s" to format(physio.mmef),
                    "DataId" to physio.dataId.ifBlank { "—" }
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    fields.forEach { (label, value) ->
                        MetricChip(label, value)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPanel(state: LabUiState, vm: LabViewModel) {
    val patient = state.patient
    val received = state.receivedPatient
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("病人信息（0x2000）", fontWeight = FontWeight.SemiBold)
        Text(
            "协议方向：从机 → 主机（设备上报）。下方展示收到的内容；主机下发仅为实验。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        if (received == null) {
            Text(
                if (state.ready) "尚未收到设备上报的 0x2000" else "连接就绪后等待设备上报",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        } else {
            Text("已收到设备上报", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }

        OutlinedTextField(
            value = patient.patientNo,
            onValueChange = { v -> vm.updatePatient { it.copy(patientNo = v.take(12)) } },
            label = { Text("病历号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = patient.dataId,
            onValueChange = { v -> vm.updatePatient { it.copy(dataId = v.take(12)) } },
            label = { Text("DataId") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = patient.heightCm,
                onValueChange = { v -> vm.updatePatient { it.copy(heightCm = v.filter(Char::isDigit).take(3)) } },
                label = { Text("身高 cm") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = patient.weightKg,
                onValueChange = { v -> vm.updatePatient { it.copy(weightKg = v.filter(Char::isDigit).take(3)) } },
                label = { Text("体重 kg") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = patient.age,
                onValueChange = { v -> vm.updatePatient { it.copy(age = v.filter(Char::isDigit).take(3)) } },
                label = { Text("年龄") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Text("性别", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = patient.sex == 0,
                onClick = { vm.updatePatient { it.copy(sex = 0) } },
                label = { Text("男") }
            )
            FilterChip(
                selected = patient.sex == 1,
                onClick = { vm.updatePatient { it.copy(sex = 1) } },
                label = { Text("女") }
            )
        }
        Text("类型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "成人", 1 to "新生儿", 2 to "儿童").forEach { (v, label) ->
                FilterChip(
                    selected = patient.type == v,
                    onClick = { vm.updatePatient { it.copy(type = v) } },
                    label = { Text(label) }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text("实验：主机写 0x2000", fontWeight = FontWeight.SemiBold)
        Text(
            "固件若只支持上报，下发会被忽略。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("建链时实验下发")
            Switch(
                checked = patient.experimentalSendOnConnect,
                onCheckedChange = { checked ->
                    vm.updatePatient { it.copy(experimentalSendOnConnect = checked) }
                }
            )
        }
        OutlinedButton(
            onClick = vm::sendPatientInfoExperimental,
            enabled = state.ready,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("实验下发当前表单")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("自定义发令", fontWeight = FontWeight.SemiBold)
        Text(
            if (state.ready) "通道就绪" else "连接并就绪后可发送",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "建链" to PmeProtocol.CMD_LINK,
                "心跳" to PmeProtocol.CMD_KEEPALIVE,
                "设备信息" to PmeProtocol.CMD_DEVICE_INFO,
                "设备状态" to PmeProtocol.CMD_DEVICE_STATUS,
                "BLE名" to PmeProtocol.CMD_BLE_NAME
            ).forEach { (label, cmd) ->
                FilterChip(
                    selected = false,
                    onClick = { vm.sendPreset(cmd) },
                    enabled = state.ready,
                    label = { Text("$label 0x${"%04X".format(cmd)}") }
                )
            }
        }
        OutlinedTextField(
            value = state.cmdIdHex,
            onValueChange = vm::setCmdIdHex,
            label = { Text("cmdId（hex）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.cmdParamsHex,
            onValueChange = vm::setCmdParamsHex,
            label = { Text("params hex（可空，如 AA BB）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        state.cmdError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Button(
            onClick = vm::sendCustomCommand,
            enabled = state.ready,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("发送请求帧")
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .width(112.dp)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 2)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2)
    }
}

@Composable
private fun LogPanel(logs: List<String>, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("GATT / 协议日志", fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onClear) {
            Icon(Icons.Default.ClearAll, contentDescription = "清空")
        }
    }
    HorizontalDivider()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(logs) { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        if (logs.isEmpty()) {
            item {
                Text(
                    "暂无日志",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun format(value: Float?): String = when {
    value == null -> "—"
    value == value.toInt().toFloat() -> value.toInt().toString()
    else -> String.format("%.1f", value)
}
