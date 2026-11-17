# EGO1 数字华容道

基于 EGO1（XC7A35T-1CSG324C）的 3×3 数字华容道项目，面向 CS207 数字逻辑课程。游戏规则、棋盘状态、步数统计与撤回逻辑在 FPGA 上执行；板载按键、电脑 GUI 和串口终端提供操作入口。

[快速开始](#快速开始) · [操作参考](#操作参考) · [构建与下载](#构建与下载) · [命令参考](#命令参考) · [文档索引](#文档索引)

## 功能概览

| 功能 | 说明 |
| --- | --- |
| 棋盘初始化 | 逐格输入 0–8，检测重复与越界；支持 GUI 自定义布局 |
| 棋子移动 | 按编号选择棋子，仅允许与空位上下左右相邻的棋子移动 |
| 提示与统计 | 可移动棋子提示、有效/无效步数、两页二进制及通关后十进制显示 |
| 通关与撤回 | 检测目标排列 `123456780`，支持撤回最近一次有效或无效移动 |
| 演示模式 | 初始化、非法移动、通关、统计、复位五个场景，可循环推进 |
| 图形界面 | 实板状态同步、鼠标与电脑键盘控制、三档可解随机开局 |
| 验证工具 | 独立规则模型、RTL 仿真、串口协议测试及实板状态对照 |

<details>
<summary>图形界面预览</summary>

![SLIDE / 09 图形界面](docs/gui-preview.jpg)

</details>

## 运行要求

| 使用场景 | 依赖 |
| --- | --- |
| 板上独立运行 | EGO1 开发板，已配置本项目 bitstream |
| GUI / 串口终端 | Linux、Python 3.10+、开发板 USB 连接；GUI 另需现代浏览器 |
| Makefile 命令 | GNU Make；`python` 命令指向 Python 3.10+ |
| FPGA 构建与 XSim 仿真 | Vivado，已安装 Artix-7 器件支持；项目验证版本为 2025.2 |
| GUI 软件检查 | 上述 Python 环境及 Node.js（用于 JavaScript 语法检查） |

GUI 与串口工具使用 Python 标准库，无需安装 pip 或 npm 依赖。当前串口实现使用 Linux 的 `termios`、`fcntl` 和 `/dev/serial/by-id/`。

## 快速开始

### 1. 获取代码

```bash
git clone https://github.com/kevin258258/cs207-project-2026.git
cd cs207-project-2026
```

### 2. 准备开发板

通过 USB 连接 EGO1，并确认 FPGA 已配置本项目固件。首次配置可选择：

- 从 [Releases](https://github.com/kevin258258/cs207-project-2026/releases) 下载 `puzzle.bit`，通过 Vivado Hardware Manager 加载到 `xc7a35t`。
- 按[构建与下载](#构建与下载)从源码生成并配置 bitstream。

FPGA 的易失配置在断电后丢失，重新上电时需要再次配置。

### 3. 启动 GUI

```bash
make gui
```

访问 [http://127.0.0.1:8765](http://127.0.0.1:8765)，点击「连接开发板」，选择串口 B 通道并连接。连接会同步现有棋局；点击「开始新棋局」可生成新布局。

GUI 仅监听本机回环地址。自定义 HTTP 端口时运行：

```bash
python tools/gui_server.py --port 8766
```

完整说明见 [GUI 与电脑键盘](docs/GUI.zh-CN.md)。

## 操作参考

### GUI 与电脑键盘

| 按键 | 功能 |
| --- | --- |
| ↑ ↓ ← → | 朝对应方向移动空位 |
| 1–8 | 选择棋子；初始化时输入数字并确认 |
| Z | 撤回最近一次移动 |
| H | 切换提示 / 统计 |
| R | 按当前难度开始新局 |
| D | 进入 / 退出演示模式 |
| 空格 | 推进演示场景 |
| Enter | 通关后切换板上数码管的棋盘 / 十进制统计 |

三档开局分别从目标盘随机移动 8、24、60 步生成，保证可解；该步数不是最短解长度。键盘事件由电脑浏览器处理，再经串口发送至 FPGA。

### 板载输入

| 控件 | 功能 |
| --- | --- |
| S6 | 全局复位 |
| S0 | 确认数字；通关后切换十进制统计 |
| S1 | 切换提示 / 统计 |
| S2 | 单步撤回 |
| S3 | 演示下一幕 |
| S4 | 游戏 / 演示模式切换，清空当前棋局 |
| 前四个相邻拨码 | 输入编号；从左到右 P5、P4、P3、P2 对应 bit3–bit0 |
| 第五个拨码 R2 | 切换计数的低 / 高四位页 |

手工初始化按从左到右、从上到下的顺序输入九个数字，每个数字用 S0 确认。例如输入 `123456708` 后，选择棋子 8 即可通关。

显示规则、引脚与完整操作见 [板卡用户指南](docs/USER_GUIDE.zh-CN.md)。

## 构建与下载

### 配置工具链

将 `VIVADO_ROOT` 设置为本机 Vivado 安装目录，使其中包含 `bin/vivado` 和 `settings64.sh`。Makefile 中预设的路径可由此变量覆盖。

```bash
# 将此示例路径替换为实际安装目录。
export VIVADO_ROOT=/opt/amd/2025.2/Vivado
```

### 仿真与构建

```bash
make sim
make build
```

构建执行综合、布局布线、时序分析和 DRC。setup、hold 或 DRC Error 检查失败时不生成 bitstream。

| 产物 | 内容 |
| --- | --- |
| `output/puzzle.bit` | FPGA 配置文件 |
| `output/puzzle_routed.dcp` | 布线后设计检查点 |
| `reports/` | 时序、资源、DRC、CDC 及验证记录 |

### 配置 FPGA

在单独终端启动硬件服务器；已有实例时无需重复启动：

```bash
"$VIVADO_ROOT/bin/hw_server" -s tcp::3121
```

在已设置 `VIVADO_ROOT` 的项目终端执行：

```bash
make program
```

配置脚本确认目标器件为 `xc7a35t`，下载后检查 `DONE=1`、`CRC_ERROR=0`。脚本只配置 FPGA 易失 SRAM，不写 SPI Flash。

## 命令参考

| 命令 | 用途 | 对实板状态的影响 |
| --- | --- | --- |
| `make gui` | 启动本地 GUI 服务 | 在用户连接、操作后控制实板 |
| `make status` | 读取实板状态 | 只读 |
| `make sim` | 规则模型与 RTL 仿真、软件测试 | 无 |
| `make check-gui` | GUI/API/串口测试与 JS 语法检查 | 无，测试使用模拟设备 |
| `make build` | 生成 bitstream 和实现报告 | 无 |
| `make program` | 经 JTAG 配置 FPGA | 重置为新配置的初始状态 |
| `make verify` | 直接通过串口执行实板验收 | 改变棋局，结束时保留演示第一幕并关闭串口写控制 |
| `make verify-gui` | 经已启动的 GUI 桥接执行实板验收 | 改变棋局，结束时保留新的热身棋局 |
| `make project` | 生成 `build/gui/puzzle.xpr` | 无 |

GUI 占用串口期间，先断开 GUI 连接再运行 `make status`、`make verify` 或其他串口工具。`make verify-gui` 使用 GUI 已建立的连接。

## 串口接口

通信参数为 **115200、8N1、无流控**。工具默认发现 FTDI B 通道 `*-if01-port0`；A 通道用于 JTAG。可显式指定设备：

```bash
python tools/board_cli.py --port /dev/ttyUSB1 --interactive
```

常用命令：

```bash
# 只读状态
python tools/board_cli.py --json

# 启用串口控制、复位、初始化 123456708、开启提示
python tools/board_cli.py ar123456708v
```

| 指令 | 功能 |
| --- | --- |
| `a` / `z` | 开启 / 关闭串口写控制 |
| `0..9`、`A..F` | 输入数字并确认；支持非法输入测试 |
| `c` / `r` / `u` | 确认 / 复位 / 撤回 |
| `v` / `h` | 提示与统计切换 / 计数高低位切换 |
| `d` / `n` | 模式切换 / 演示推进 |
| `?` | 查询原子状态快照，始终可读 |

状态帧格式和输入仲裁见 [设计说明中的 UART 协议](docs/DESIGN.zh-CN.md#uart-协议)。

## 工程结构

```text
.
├── rtl/          # FPGA 游戏规则、输入过滤、显示与 UART
├── constraints/  # EGO1 引脚与时序约束
├── gui/          # 浏览器界面
├── tools/        # 串口客户端、GUI 桥接、独立模型与验收工具
├── sim/          # RTL testbench 与 Python 测试
├── scripts/      # Vivado 构建、配置与仿真脚本
├── docs/         # 操作、设计与演示文档
├── reports/      # 已记录的验证结果与校验清单
└── Makefile      # 常用命令入口
```

`build/`、`output/` 和本地课程附件目录 `reference/` 不进入版本控制；已构建 bitstream 通过 Releases 提供。

## 验证记录

以下为 2026-11-17 已记录的结果，完整范围和复现方法见 [验证文档](docs/VALIDATION.zh-CN.md)。

| 检查 | 结果 |
| --- | --- |
| 独立规则模型与 RTL 对照 | 20,106 组向量通过 |
| GUI/API/串口软件测试 | 14 项通过 |
| 直接串口实板对照 | 1,254 次通过 |
| GUI 桥接实板对照 | 114 次通过 |
| 布线后时序，100 MHz | WNS +0.646 ns，WHS +0.122 ns |
| 物理设计规则 | DRC 0 项违规 |

[GitHub Actions](https://github.com/kevin258258/cs207-project-2026/actions) 使用 Icarus Verilog 运行 RTL 仿真，同时执行 Python 测试和 JS 语法检查。Vivado 实现与实板验收由本地工具链完成。

## 设计约定

- **棋盘表示**：0 为空位，板上数码管灭灯；目标排列为 `123456780`。
- **初始化**：检测 0–8 的唯一性；自定义输入允许不可解排列，GUI 会给出提示。
- **计数范围**：有效与无效步数独立计数，到 99 后饱和。LED 默认展示低四位，高页展示高四位，完整值为 `高页 × 16 + 低页`。
- **撤回范围**：保存最近一次游戏确认操作的棋盘、计数和错误状态；撤回后不再生成新的撤回历史。
- **统计与计时**：步数来自 FPGA；GUI 计时属于浏览器会话，刷新页面后重新开始。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [GUI 与电脑键盘](docs/GUI.zh-CN.md) | 连接、开局、快捷键与会话行为 |
| [板卡用户指南](docs/USER_GUIDE.zh-CN.md) | 实体输入、显示映射、引脚与操作示例 |
| [设计说明](docs/DESIGN.zh-CN.md) | 模块划分、需求对应、协议与边界决策 |
| [验证记录](docs/VALIDATION.zh-CN.md) | 测试覆盖、物理实现结果与复现方法 |
| [演示与答辩提纲](docs/DEMO_SCRIPT.zh-CN.md) | 实板录制分镜和设计问题 |
