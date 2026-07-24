# 安巢 AnNest

> 端侧视觉智能老人监护 APP — 跌倒检测 + 语音求救 + 静止告警三重守护

[![Platform](https://img.shields.io/badge/Android-8.0%2B-34A853)](https://developer.android.com)
[![SDK](https://img.shields.io/badge/compileSdk-35-blue)](https://developer.android.com/tools/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Commercial-red)]()

## 产品定位

面向独居老人的 7×24 离线智能监护应用，基于端侧 AI 视觉与语音识别，无需联网即可完成跌倒检测、语音求救识别、长时间静止告警，并通过 SMS + 电话自动通知紧急联系人。

## 核心功能

### 三重守护

| 功能 | 技术 | 触发条件 |
|------|------|---------|
| 跌倒检测 | MediaPipe Pose + 加速度计融合 | SVM > 2g 候选 + 姿态躺下持续 3s（候选）/ 8s（无候选） |
| 语音求救 | Vosk 离线中文模型 | 识别"救命/帮帮我/救救我/来人啊/摔倒了"等 7 个关键词 |
| 静止告警 | 30 帧姿态位移方差 | 平均帧间位移 < 0.005 持续可配置时长（5-60 分钟） |

### 告警通道
- SMS 群发所有紧急联系人（sent + delivered 双回执）
- 自动拨打第一联系人电话（CALL_PHONE）
- 本地高优先级通知

### 激活机制
- 7 天免费试用
- 离线激活码（SHA-256 + 4 字节 checksum）
- Polygon NFT 许可证（导入钱包私钥，AES256_GCM 加密存储）

## 技术栈

| 层 | 技术 |
|----|------|
| UI | Material Design 3 + AppCompat + ConstraintLayout |
| 相机 | CameraX 1.4.1 |
| AI 推理 | MediaPipe Tasks Vision 0.10.14 |
| 语音 | Vosk Android 0.3.47（离线中文） |
| 传感器 | SensorManager（加速度计融合） |
| 加密 | BouncyCastle + AndroidX Security Crypto |
| Web3 | OkHttp 4.12 + Polygon RPC |
| 异步 | Kotlin Coroutines 1.7.3 |

## 架构

采用 **多 Activity + 前台 Service** 的轻量架构，按职责分包为 `ui` / `service` / `pose` / `voice` / `data` 五层，核心检测器均为双重检查锁单例（`PoseAnalyzer` / `VoiceDetector` / `LicenseManager`），共享 `applicationContext`。

- **UI 层（`ui`）**：`MainActivity` 为入口，负责权限申请与主界面；`ContactActivity` 管理紧急联系人，`SettingsActivity` 配置静止告警时长等参数，`CameraPreviewActivity` 提供全屏相机预览，`ActivationDialogFragment` 处理离线激活码输入。
- **服务层（`service`）**：`ElderMonitorService` 为前台服务（`foregroundServiceType=camera|microphone`），承担 7×24 监护保活，统一编排姿态/语音检测并下发 SMS 群发 + 高优先级通知；同文件内的 `BootReceiver` 监听 `BOOT_COMPLETED` 实现开机自启。
- **姿态层（`pose`）**：`PoseAnalyzer` 封装 MediaPipe `PoseLandmarker`（IMAGE 模式，单人体），消费 CameraX 帧后计算身体包围盒宽高比与帧间位移，输出 `onFallDetected` / `onStillnessDetected` / `onStateChange` 回调。
- **语音层（`voice`）**：`VoiceDetector` 基于 Vosk 离线中文模型，在 IO 协程中加载 assets 内模型并持续识别，命中 7 个求救关键词之一即触发 `onSOSDetected`。
- **数据层（`data`）**：`LicenseManager` 管理 7 天试用期与 `EG00-XXXX-XXXXXXXX` 离线激活码（SHA-256(SALT+payload+SALT) 校验）；`Web3LicenseManager` 对接 Polygon NFT 许可证，私钥经 `EncryptedSharedPreferences` (AES256_GCM) 加密存储；`AppConfig` 集中应用配置。

**数据流**：CameraX 帧流 → `PoseAnalyzer.processBitmap` → `analyzeResult` 计算宽高比/位移 → 跌倒或静止回调 → `ElderMonitorService` 触发 SMS 群发与本地通知；并行地 `VoiceDetector` 监听麦克风 → 命中关键词 → `onSOSDetected` 回调 → 同一告警通道下发。

## 权限说明

| 权限 | 用途 |
|------|------|
| CAMERA | 姿态识别 |
| RECORD_AUDIO | 语音求救识别 |
| SEND_SMS | 紧急告警短信 |
| CALL_PHONE | 自动拨打紧急联系人 |
| FOREGROUND_SERVICE | 7×24 监护保活 |
| WAKE_LOCK | 锁屏持续监测 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| POST_NOTIFICATIONS | 告警通知（Android 13+） |
| VIBRATE | 告警震动反馈 |

## 构建

### 环境要求
- Android Studio Ladybug+
- JDK 17
- Android SDK 35
- NDK（仅 arm64-v8a）

### 配置签名
```bash
cp local.properties.example local.properties
# 编辑 local.properties 填入 keystore 密码
```

### Debug 构建
```bash
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

### Release 构建
```bash
./gradlew assembleRelease
```

### 运行测试
```bash
./gradlew test
```

## 项目结构

```
app/src/main/java/com/elderguard/care/
├── data/                            # 数据与激活
│   ├── AppConfig.kt                 # 应用配置
│   ├── LicenseManager.kt            # 离线激活码 + 7 天试用管理
│   └── Web3LicenseManager.java      # Polygon NFT 许可证（钱包私钥加密存储）
├── pose/
│   └── PoseAnalyzer.kt              # MediaPipe 姿态识别 + 跌倒/静止检测（单例）
├── voice/
│   └── VoiceDetector.kt             # Vosk 离线中文语音求救识别（单例）
├── service/
│   └── ElderMonitorService.kt       # 前台监护服务 + BootReceiver（开机自启，同文件）
└── ui/
    ├── MainActivity.kt              # 主界面 / 权限申请
    ├── ContactActivity.kt           # 紧急联系人管理
    ├── SettingsActivity.kt          # 静止时长等设置
    ├── CameraPreviewActivity.kt     # 全屏相机预览
    └── ActivationDialogFragment.kt  # 激活码输入弹窗
```

## 隐私保护

- 摄像头画面仅本地分析，不联网不上传
- 语音识别完全离线（Vosk 本地模型）
- 钱包私钥用 EncryptedSharedPreferences (AES256_GCM) 加密存储
- 联系人数据仅本地保存，allowBackup=false
- 无任何后台数据上报

## 许可证

商业专有软件 © 2026 clawclaw.tech
