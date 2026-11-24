# EGO1 安卓华容道 0.2

支持模拟板、USB OTG 直连 EGO1，以及经典蓝牙串口 SPP。包含二维棋盘、逐格摆盘、提示、0–99 统计、撤回、通关、五幕演示和板载 LED 展示控制。Android 8.0 及以上可安装。

从 [手机测试 ZIP](deliverables/EGO1-Puzzle-Android-0.2.zip) 解压并安装 APK。[手机操作说明](docs/phone-test.md) 包含接线和验收步骤。

## USB

手机用 OTG 数据线连接开发板，开发板使用自身电源。点「USB 连接」，选择 EGO1 并允许访问设备。驱动支持 FT2232H（VID 0403 / PID 6010），只打开 UART B，自动配置 115200、8N1、无流控。板卡须已烧录本仓库华容道固件。

## 蓝牙

使用提供经典蓝牙 SPP 服务的串口模块，例如 HC-05。将模块 UART 配置为 115200、8N1，使用 3.3 V 电平并共地，TX 接 FPGA RX、RX 接 FPGA TX。UART 路径按板卡手册确认，避免模块和板载 USB UART 同时驱动 FPGA RX。在系统设置里配对模块，再在 App 中点「蓝牙连接」。Android 12 及以上请求附近设备连接权限。BLE 模块需要其专用 GATT 协议。

## 同步和断线

真实连接使用仓库的 [UART v1 协议](../docs/DESIGN.zh-CN.md#uart-协议)。连接时开启串口控制并读取原棋局。每次查询完成后间隔 350 ms 再查询，落子后立即读取确认状态。板载按键和复位也会同步到手机。

查询与操作共用单线程队列，最多接受一项待处理操作。写命令不自动重发，避免超时后重复计步。帧解析支持分片、噪声、XOR 校验和版本检查。1.5 秒内没有有效状态则关闭连接、禁用棋盘，保留最后确认画面。点击连接按钮可重新同步。切入后台或屏幕重建后需要重连。正常断开时尝试发送 z 释放串口写控制。

模拟板使用与板端一致的 0–99 饱和计数和五幕演示，第五幕可直接推进重播。

## 构建和测试

需要 JDK 17、Android SDK 36 和 Build Tools 36.1.0，在 android 目录运行。

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
```

BoardSmokeCheck 检查规则，ProtocolCheck 检查帧解析、超时、串行命令和断开。ConnectionTestRunner 在已安装的 Activity 上使用生产版 RemoteBoard 和协议解析器验证模拟串口及界面。测试串口注入代码位于 src/androidTest，仅包含在测试 APK 中。

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w edu.sustech.cs207.egopuzzle.test/edu.sustech.cs207.egopuzzle.ConnectionTestRunner
```

软件测试覆盖协议和界面，实物 USB、蓝牙模块及真实手机需按手机说明现场验收。

UART 状态帧测试向量来自本仓库 FPGA 顶层仿真，可在安装 Icarus Verilog 后运行 `python tools/capture-rtl-frames.py` 重新采集，再把 `tools/rtl-frames.txt` 作为 ProtocolCheck 的参数。采集过程在临时目录运行，不修改 FPGA 源文件。
