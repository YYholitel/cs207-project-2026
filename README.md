# CS207 2026 Fall · EGO1 数字华容道

在 EGO1 / XC7A35T-1CSG324C 上实现 3×3 数字华容道，采用课程指定的八位数码管与十六个 LED，支持独立板上操作。UART 上位机可显示二维棋盘，也用于直接验收 FPGA 上运行的逻辑。

## 图形界面与电脑键盘

```bash
make gui
```

打开 <http://127.0.0.1:8765> 并连接开发板，即可使用动画棋盘、三档可解随机开局、自定义布局、完整统计、撤回和演示。**方向键移动空位，1–8 选棋子，Z 撤回，H 提示，R 新局，D 演示，空格推进，Enter 查看通关统计。**

[GUI 详细操作](docs/GUI.zh-CN.md)。只使用 Python 标准库；现有 bitstream 直接兼容。本版本按用户选择支持电脑键盘。

## 先在板上试玩

工程下载后，验收工具会留下演示第一幕 `123456708`，提示灯对应 5、7、8，UART 控制已关闭。

- 按 **S3**：依次演示非法移动 1 → 合法移动 8 / 通关 → 十进制统计 → 全暗复位 → 重新开始。
- 按 **S1**：切换左下方八灯的提示 / 统计；第一次为提示，第二次为统计。
- 按 **S6**：全局复位，返回手工游戏初始化。
- 手工游戏：从左到右的前四个拨码开关（P5、P4、P3、P2）组成四位数，左侧为最高位；设置后按 **S0** 确认。依次输入九个不重复的 0–8。游戏中同样输入要移动的棋子编号后按 S0。
- 按 **S2**：撤回最近一次有效或无效移动，棋盘、计数、错误状态一起恢复。通关后也可撤回最后一步。
- 按 **S4**：切换手工 / 演示模式；切换会清空原游戏，再按 S3 开始演示。

[详细板卡操作与引脚](docs/USER_GUIDE.zh-CN.md) · [设计与需求对应](docs/DESIGN.zh-CN.md) · [答辩与录屏脚本](docs/DEMO_SCRIPT.zh-CN.md) · [验证结果](docs/VALIDATION.zh-CN.md)

## 构建、仿真、下载

需要 Vivado（本机使用 2025.2）、Artix-7 器件支持以及 Python 3.10+。上位机只使用 Python 标准库，无需 pip 包。默认 Vivado 路径在 Makefile，可通过 `VIVADO_ROOT` 覆盖。

```bash
make sim                         # 独立模型 + 核心 / 顶层 XSim 测试
make build                       # 综合、布局布线、时序/DRC、生成 bitstream
# 另开终端启动 Vivado 硬件服务器：
"$VIVADO_ROOT/bin/hw_server" -s tcp::3121
make program                     # 只配置 FPGA 易失 SRAM，不写 SPI Flash
make verify                      # 实板 UART 验收，最后留下演示第一幕
make status                      # 只读实板状态
make project                     # 生成 build/gui/puzzle.xpr，便于 Vivado GUI 阅读
```

未设环境变量时，可直接用 `/home/tfx/opt/amd/2025.2/Vivado/bin/hw_server -s tcp::3121`。已有硬件服务器时无需重复启动。

生成物：`output/puzzle.bit`、`output/puzzle_routed.dcp`。构建会在时序或 DRC 错误时失败，避免下载不满足条件的产物。编译缓存与课程原始资料均忽略，不上传。

GitHub Actions 使用独立的 Icarus Verilog 再运行相同 RTL 仿真及主机协议测试；它不代替 Vivado 物理实现与实板验收。

## UART 二维棋盘

```bash
python tools/board_cli.py --interactive
# 输入 a 开启高级外设；然后输入 r123456708v 即可初始化并显示提示。
# 也可一次执行：
python tools/board_cli.py ar123456708v
python tools/board_cli.py --json
```

自动选取 FTDI **B 通道** `*-if01-port0`，或显式传 `--port /dev/ttyUSB1`。不要用 A 通道，它是 JTAG。通信为 115200、8N1，无流控。

命令：`a` 开启 / `z` 关闭 UART 控制，`r` 复位，`0..9,A..F` 输入并确认，`c` 确认/通关后统计切换，`u` 撤回，`v` 提示/统计，`d` 模式切换，`n` 演示下一幕，`h` LED 二进制高四位页，`?` 只读状态。关闭高级外设后实体输入仍可完整操作，`?` 始终可读。

## 约定和边界

- 空格 0 在数码管上灭灯；初始化非法输入显示 E，第九格非法时四灯全亮。
- 任意 0–8 的排列均允许初始化，符合原文合法性定义；不会额外拒绝不可解排列。
- 步数计到 99 后保持 99。四个 LED 的默认页显示低四位，第五个拨码开关（R2）切到高四位；完整值 = 高页 × 16 + 低页。00–15 与题目原始四灯方案完全一致，通关后十进制始终显示完整的 00–99。
- 课程演示文字中的“合法移动后无效步数 +1”应为“有效步数 +1”。本实现按规则处理；通关棋盘的提示为 6、8，位图 `0000_0101`。
- 课程远程仓库/视频具体规范尚待课程后续发布；当前为用户要求建立的私有 GitHub 仓库，尚未代为进行团队登记或课程提交。

原始要求通过 Blackboard「数字逻辑（2026秋）→ labs & Project」读取，下载日期为 2026-11-17。本地 `reference/` 保留原 PDF、官方 XDC 和参考 HTML，未进入 Git 历史。
