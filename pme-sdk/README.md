# PME SDK

PME 呼吸监测设备 **BLE 对接库**（Android Library），可被多个工程以 `project(":pme-sdk")` 或源码拷贝方式依赖。

> 仅供开发联调与互操作参考，**非临床用途**。完整声明见仓库根目录 [README · 声明与免责](../README.md#声明与免责)。

## 接入

```kotlin
// settings.gradle.kts
include(":pme-sdk")

// app/build.gradle.kts
dependencies {
    implementation(project(":pme-sdk"))
}
```

## 快速使用

```kotlin
val client = PmeClient(context)

client.onPhysioData = { data ->
    // SpO2 / 血压 / 体温等；无效字段为 null，可用 mergePreserve 保留上次值
}

client.onPatientInfo = { info ->
    // 0x2000：协议为从机→主机上报
}

client.onDeviceInfo = { info -> /* serialNo / hardwareVersion / softwareVersion */ }
client.onDeviceStatus = { st -> /* batteryLabel / btLabel（电池为档位枚举，非 %） */ }
client.onBleName = { n -> /* n.name */ }
client.onRawFrame = { direction, bytes -> /* TX / RX */ }

client.scanner.startScan(
    targetNames = listOf("PME"),
    onDeviceFound = { /* BleScanResult（含 BluetoothDevice） */ },
    onScanFailed = { },
    onScanStopped = { }
)

// 常规：建链即可，病人信息等设备上报
client.connect(bluetoothDevice)

// 实验：主机写 0x2000（固件可能忽略）
// client.connect(device, PmePatientInfo(...))
// client.sendPatientInfo(info)

client.sendRequest(0x1000)
client.disconnect()
```

## 能力

| API | 说明 |
|-----|------|
| `PmeClient` | 统一入口：扫描 + 连接 + 回调 |
| `BluetoothScanner` | BLE 扫描（优先广播名过滤，可刷新 RSSI） |
| `BluetoothGattManager` | 底层 GATT（一般用 PmeClient 即可） |
| `PmeCodec` | 每连接一份的粘包缓冲与序号 |
| `PmeProtocol` | 常量 / CRC / 病人·设备编解码 |
| `PmePhysioData` | 0x2001 生理数据 |
| `PmePatientInfo` | 0x2000 病人信息（以设备上报为主） |
| `PmeDeviceInfo` / `PmeDeviceStatus` / `PmeBleName` | 0x1000 / 0x1001 / 0x1100 |

Host App 需在运行时申请蓝牙权限（本模块 Manifest 已声明，会合并进 App）。

## License

与仓库相同：[Apache License 2.0](../LICENSE)
