# PME Lab

开源的 **PME 呼吸监测设备 BLE 联调工具**（Android）。

用于本地联调：扫描、建链、协议收发、生理数据与设备状态展示。  
业务侧可只依赖 `:pme-sdk`，不必带上本 App。

> **重要**：本项目仅供开发联调与互操作参考，**不能用于临床诊断、治疗或监护决策**。详见下方 [声明与免责](#声明与免责)。

## 功能

| 页签 | 能力 |
|------|------|
| **扫描** | BLE 扫描（可按广播名过滤，默认 `PME`），点选连接 |
| **数据** | 展示 `0x2001` 生理数据（`mergePreserve`）；设备信息 / 电量等 |
| **控制** | 编辑并下发病人信息 `0x2000`；自定义 cmd + hex 参数发令 |
| **日志** | GATT / 协议 TX·RX 摘要 |

建链流程摘要：CCCD 订阅 → `0x8001` → 可选 `0x2000` → 周期 `0x8002` → 收 `0x2001`。

## 模块

| 模块 | 说明 |
|------|------|
| [`:pme-sdk`](pme-sdk/README.md) | 可复用 BLE + 协议库（`PmeClient`） |
| `:app` | Compose 联调 UI |

## 环境

- Android Studio（建议较新版本）/ AGP 9.x
- JDK 17+（推荐 Android Studio 自带 JBR）
- minSdk 24，targetSdk 36

## 构建

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

SDK 单元测试：

```bash
./gradlew :pme-sdk:testDebugUnitTest
```

## 使用

1. 授予蓝牙权限并打开系统蓝牙  
2. **扫描** → 开始扫描 → 点选设备  
3. （可选）先在 **控制** 页编辑病人信息；默认建链时自动下发 `0x2000`  
4. **数据** 查看实时值；**控制** 可再发 `0x2000` 或自定义命令  
5. **日志** 排查建链 / 写特征 / 帧解析问题  

## SDK 快速接入

```kotlin
val client = PmeClient(context)

client.onPhysioData = { data ->
    val merged = data.mergePreserve(prev)
}

client.onDeviceInfo = { info -> /* info.text */ }
client.onDeviceStatus = { st -> /* st.batteryPercent / st.btState */ }
client.onRawFrame = { direction, bytes -> /* "TX" / "RX" */ }

client.scanner.startScan(
    targetNames = listOf("PME"),
    onDeviceFound = { /* BleScanResult（含 BluetoothDevice） */ },
    onScanFailed = { },
    onScanStopped = { }
)

client.connect(device, PmePatientInfo(
    patientNo = "P001", sex = 0, type = 0,
    heightCm = 170, weightKg = 65, age = 40,
    dataId = "13800138000"
))

client.sendRequest(0x1001) // 例：请求设备状态
client.disconnect()
```

更多 API 见 [`pme-sdk/README.md`](pme-sdk/README.md)。

## 协议要点（摘要）

公开的互操作要点（便于联调，**非**厂商官方规范全文）：

- Service `69400001-b5a3-f393-e0a9-e50e24dcca99`
- Notify `69400002-…` / Write `69400003-…`
- 固定帧头 12 字节；`dataLen = 4 + params`；帧尾 CRC16（Modbus 风格）
- 常见命令：`0x8001` 建链、`0x8002` 保活、`0x2000` 病人信息、`0x2001` 生理数据、`0x1000`/`0x1001` 设备信息/状态
- 无效生理字段多为 `0xFFFF`，展示层可用 `mergePreserve` 保留上次有效值

## 声明与免责

1. **非医疗器械 / 非临床用途**  
   本软件不提供诊断、治疗、监护或任何医疗建议；界面上的 SpO₂、血压、体温等仅为联调展示。不得用于患者诊疗决策。

2. **商标与品牌**  
   「PME」及设备相关名称仅为描述目标硬件时的常用称呼。本仓库**不**声称获得商标权人授权，**不**构成官方产品、附属或背书。若权利人有异议，请联系维护者协商处理（更名、下架引用等）。

3. **知识产权与设备协议**  
   本仓库实现面向本地 BLE 互操作与调试。使用者须自行确认：对目标设备进行连接与协议交互是否已获授权、是否符合当地法律及采购/保密合同。作者不对第三方将本代码用于未授权设备、破解防护或绕过商业限制负责。

4. **无担保**  
   按 [Apache License 2.0](LICENSE) 以「按现状」提供，不保证协议兼容性、数据正确性或持续可用。

5. **隐私**  
   联调默认病人信息（如 `LAB0001`）为占位数据。请勿填入真实患者隐私信息并上传日志或截图到公共渠道。

6. **范围**  
   不含厂商私有云、账号体系、患者管理系统等闭源业务；包名中的历史片段不代表与任何商业实体存在隶属关系。

## License

[Apache License 2.0](LICENSE)

Copyright 2026 Abysmus / PME Lab contributors
