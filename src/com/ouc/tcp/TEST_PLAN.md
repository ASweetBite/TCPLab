# TCPLab 测试计划：Reno / NewReno 对比

## 1. 基础无差错测试

**目标**：确认基本发送、ACK、窗口滑动与数据交付正常。

建议配置：

```ini
protocol=TCP_NEW_RENO
tcp.log=tcp_newreno_clean.log
```

预期现象：

- 日志中主要包含 `SEND`、`NEW_ACK`、`CWND_UPDATE`、`WINDOW_SLIDE`
- 不应出现 `TIMEOUT`、`FAST_RETRANS`、`PARTIAL_ACK`
- `cwnd` 先慢启动增长，达到 `ssthresh` 后进入拥塞避免

## 2. 单包丢失测试

**目标**：验证 Reno / NewReno 的快速重传都能正常触发。

预期现象：

- 接收端持续返回重复 ACK
- 发送端出现 3 次 `DUP_ACK`
- 随后出现 `ENTER_FR` 和 `FAST_RETRANS`
- 如果后续 ACK 一次性覆盖 `recoverPoint`，NewReno 会出现 `FULL_ACK` 并退出快速恢复

## 3. 同一窗口内连续多包丢失测试

**目标**：验证 NewReno 对多包丢失的优化。

建议分别运行：

```ini
protocol=TCP_RENO
tcp.log=tcp_reno_multi_loss.log
```

和：

```ini
protocol=TCP_NEW_RENO
tcp.log=tcp_newreno_multi_loss.log
```

预期对比：

- Reno：收到新 ACK 后立即 `EXIT_FR`，后续丢包可能再次触发快重传，窗口可能多次减半
- NewReno：收到 partial ACK 后出现 `PARTIAL_ACK`，继续保持 `FastRecovery`
- NewReno：对新的 `sendBase` 立即触发 `NEWRENO_RETRANS`
- NewReno：直到 `ACK >= recoverPoint` 才出现 `FULL_ACK` 和 `EXIT_FR`

## 4. 超时重传测试

**目标**：验证严重拥塞场景下窗口回退。

预期现象：

- 出现 `TIMEOUT`
- `ssthresh` 被设置为当前 `cwnd / 2`
- `cwnd` 回到 1 MSS
- 触发 `RETRANSMIT`

## 5. 可视化测试

使用 `RenoLogVisualizer.java` 打开日志文件，检查：

- `Packet Animation`：DATA、ACK、DUP_ACK、重传包动画是否正确
- `cwnd / ssthresh Chart`：是否能看到慢启动、拥塞避免、快恢复和超时回退
- `Packet Timeline`：是否能看到 DATA、ACK、FAST_RETRANS、NEWRENO_RETRANS、TIMEOUT 等时序关系
- 底部发送窗口：窗口滑动与 `cwnd` 黄框是否同步变化

## 6. 自动化日志分析

运行：

```bash
python tools/analyze_tcp_log.py tcp_newreno.log --csv events.csv
```

重点查看：

- `PARTIAL_ACK` 数量
- `FULL_ACK` 数量
- `FAST_RETRANS` / `NEWRENO_RETRANS` 数量
- `cwnd Drop Points`
- `TIMEOUT` 是否过多
