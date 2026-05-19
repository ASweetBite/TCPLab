
# TCPLab：TCP Reno 协议迭代实现与发送窗口可视化

![Java](https://img.shields.io/badge/Language-Java-orange)
![Protocol](https://img.shields.io/badge/Protocol-TCP%20Reno-blue)
![Course Project](https://img.shields.io/badge/Type-Course%20Project-green)

## 项目简介

本项目是中国海洋大学计算机网络课程大作业的实验实现，基于 Java 完成可靠数据传输协议与 TCP 拥塞控制机制的逐步迭代开发。

项目从基础的可靠数据传输协议出发，依次实现 RDT、Go-Back-N、Selective Repeat、TCP Tahoe，并最终完成 TCP Reno 协议的核心机制，包括滑动窗口、累计确认、超时重传、慢启动、拥塞避免、快速重传和快速恢复等功能。

在协议实现的基础上，项目进一步增加了 TCP Reno 运行日志记录与发送窗口可视化模块，用于直观展示拥塞窗口变化、发送窗口滑动、重复 ACK、超时重传和快速重传等关键过程，方便分析协议运行状态与调试拥塞控制逻辑。

## 核心功能

### 1. 可靠数据传输协议迭代实现

项目采用迭代开发模式，按照协议复杂度逐步扩展：

* RDT1.0：理想信道下的可靠传输
* RDT2.0：基于校验和处理比特错误
* RDT2.1：引入序号机制，解决 ACK/NAK 出错导致的重复交付问题
* RDT2.2：取消 NAK，通过重复 ACK 触发重传
* RDT3.0：引入超时重传机制，处理丢包场景
* Go-Back-N：实现发送窗口、累计确认与批量重传
* Selective Repeat：实现接收窗口、乱序缓存与选择性确认
* TCP Tahoe：加入慢启动、拥塞避免和超时后的拥塞窗口回退
* TCP Reno：在 Tahoe 基础上加入快速重传与快速恢复机制

### 2. TCP Reno 拥塞控制

TCP Reno 是本项目的主要完成版本，核心机制包括：

* 维护 `cwnd` 和 `ssthresh`，实现拥塞窗口动态调整
* 慢启动阶段：每收到一个新的 ACK，拥塞窗口按 MSS 增长
* 拥塞避免阶段：拥塞窗口按加法增大方式线性增长
* 重复 ACK 统计：通过 `dupAckCount` 记录重复确认数量
* 快速重传：连续收到 3 个重复 ACK 后立即重传丢失报文段
* 快速恢复：进入快速恢复状态后调整 `cwnd`，避免像 Tahoe 一样直接回退到 1 MSS
* 超时处理：超时后认为发生严重拥塞，更新 `ssthresh` 并将 `cwnd` 重置为 1 MSS

### 3. 滑动窗口管理

项目分别实现了发送窗口与接收窗口：

* `SendWindow`：维护发送端窗口、缓存未确认数据包、处理 ACK、控制重传和拥塞窗口变化
* `ReceiveWindow`：维护接收端窗口，缓存窗口内报文段，并在收到连续数据后向上层交付
* 采用字节序号统一管理窗口边界，避免包序号和字节序号混用导致的窗口滑动错误
* 支持累计确认逻辑，使发送方能够一次性确认某个 ACK 之前的所有报文段

### 4. TCP 校验和计算

项目实现了 TCP 报文段校验和计算，用于检测传输过程中的比特错误。

校验范围包括：

* TCP 首部中的 `seq`
* TCP 首部中的 `ack`
* TCP 数据字段

实现时将 32 位字段拆分为两个 16 位部分进行累加，并通过进位折叠与取反得到最终校验和。

### 5. TCP Reno 日志记录

为支持协议调试与可视化分析，发送窗口在运行过程中记录关键事件，例如：

* `SEND`：发送新的数据包
* `NEW_ACK`：收到新的 ACK
* `DUP_ACK`：收到重复 ACK
* `FAST_RETRANS`：触发快速重传
* `EXIT_FR`：退出快速恢复
* `TIMEOUT`：发生超时
* `RETRANSMIT`：执行重传
* `WINDOW_SLIDE`：发送窗口滑动

日志中记录的信息包括：

* 当前时间
* 事件类型
* `sendBase`
* `nextSeq`
* `cwnd`
* `ssthresh`
* TCP 当前状态
* 事件说明信息

示例格式：

```text
2026-01-11 14:02:07:346    EVENT:DUP_ACK        base:21401    next:21801    cwnd:1400    ssthresh:1600    state:CongAvoid    info:AckNum: 21301 count: 3
```

### 6. 发送窗口可视化

项目额外实现了 `RenoLogVisualizer` 可视化工具，用于解析 `tcp_reno.log` 并动态展示 TCP Reno 的运行过程。

可视化模块支持：

* 动画展示数据包、ACK、重复 ACK 和重传包的传输过程
* 实时显示 `cwnd`、`ssthresh`、`sendBase` 和 `nextSeq`
* 绘制发送窗口状态
* 高亮显示异常事件，例如超时、重复 ACK、快速重传和重传
* 支持自动在关键错误事件处暂停，便于观察协议状态

## 技术栈

* Java
* Java Swing
* TCP Reno
* Sliding Window
* Congestion Control
* Log Parsing
* Protocol Visualization

## 项目结构

```text
TCPLab
├── src
│   └── com
│       └── ouc
│           └── tcp
│               ├── test
│               │   ├── CheckSum.java          # TCP 校验和计算
│               │   ├── SendWindow.java        # 发送窗口与 TCP Reno 拥塞控制
│               │   ├── ReceiveWindow.java     # 接收窗口与数据交付
│               │   ├── TCP_Sender.java        # 发送端逻辑
│               │   ├── TCP_Receiver.java      # 接收端逻辑
│               │   └── TestRun.java           # 实验入口
│               │
│               └── visual
│                   └── RenoLogVisualizer.java # TCP Reno 日志可视化工具
│
├── Config.ini              # 实验配置文件
├── tcp_reno.log            # TCP Reno 运行日志
├── recvData.txt            # 接收端输出数据
├── project.puml            # 项目类图描述
├── project.png             # 项目类图
└── README.md
```

## 运行方式

### 1. 克隆项目

```bash
git clone https://github.com/ASweetBite/TCPLab.git
cd TCPLab
```

### 2. 导入 Java 项目

可以使用 IntelliJ IDEA 或 Eclipse 打开项目。

项目中包含 `.project`、`.classpath` 和 `.iml` 等配置文件，推荐直接作为 Java 工程导入。

### 3. 运行协议实验

运行实验入口：

```text
src/com/ouc/tcp/test/TestRun.java
```

运行后，程序会根据实验框架模拟发送端与接收端之间的数据传输，并在项目根目录生成或更新：

```text
tcp_reno.log
recvData.txt
```

### 4. 运行可视化工具

运行：

```text
src/com/ouc/tcp/visual/RenoLogVisualizer.java
```

启动后选择 `tcp_reno.log` 文件，即可查看 TCP Reno 的动态运行过程。

## TCP Reno 实现要点

### 慢启动

初始时：

```text
cwnd = 1 MSS
ssthresh = 16 MSS
```

当 `cwnd < ssthresh` 时，协议处于慢启动阶段。每收到一个新的 ACK，`cwnd` 增加 1 MSS。

### 拥塞避免

当 `cwnd >= ssthresh` 时，协议进入拥塞避免阶段。每收到一个新的 ACK，`cwnd` 按加法增大的方式缓慢增长：

```text
cwnd += MSS * MSS / cwnd
```

### 快速重传

当发送方连续收到 3 个重复 ACK 时，认为某个报文段可能丢失，但网络并未完全拥塞，因此立即重传 `sendBase` 对应的报文段，而不等待超时。

### 快速恢复

触发快速重传后，协议进入快速恢复状态：

```text
ssthresh = cwnd / 2
cwnd = ssthresh + 3 * MSS
```

在快速恢复阶段，每收到一个额外的重复 ACK，`cwnd` 继续增加 1 MSS。

当收到新的 ACK 后，说明丢失报文段已经被正确确认，协议退出快速恢复，并回到拥塞避免阶段。

### 超时重传

如果计时器超时，说明网络可能发生严重拥塞。此时执行：

```text
ssthresh = cwnd / 2
cwnd = 1 MSS
```

随后重传发送窗口中最早未确认的报文段。

## 实验结果说明

在模拟丢包、错包和延迟的实验环境下，项目能够观察到以下现象：

* 丢包后，接收方持续返回上一次正确收到的 ACK
* 发送方收到 3 个重复 ACK 后触发快速重传
* 快速重传后进入快速恢复阶段，而不是直接回到 1 MSS
* 收到新的 ACK 后退出快速恢复，并继续进行拥塞避免
* 超时情况下，发送方将 `cwnd` 重置为 1 MSS，重新进入慢启动阶段
* 发送窗口会随着累计确认不断向前滑动

## 项目特点

* 采用迭代开发模式，从基础可靠传输逐步扩展到 TCP Reno
* 将协议理论与代码实现对应起来，便于理解每一阶段新增机制
* 实现了发送窗口、接收窗口、累计确认、超时重传和拥塞控制等核心模块
* 增加日志记录与可视化工具，使协议运行过程更加直观
* 通过可视化方式辅助定位重复 ACK、快速重传、超时重传等关键事件

## 后续优化方向

* 实现 TCP NewReno，优化同一窗口内多个报文段连续丢失时的恢复效率
* 增加 `cwnd` 和 `ssthresh` 的折线图展示
* 增加包传输时序图，展示 DATA、ACK、重传和丢包之间的关系
* 支持从配置文件中选择不同协议版本进行对比实验
* 增加更细粒度的测试用例与自动化日志分析脚本

## 项目定位

本项目主要用于计算机网络课程实验与 TCP 协议机制学习，重点展示可靠传输、滑动窗口和 TCP Reno 拥塞控制机制的实现过程。项目并非生产环境 TCP 协议栈，而是面向教学、实验和协议理解的模拟实现。
