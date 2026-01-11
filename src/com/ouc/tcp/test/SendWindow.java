package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

import java.util.HashMap;
import java.util.Map;

public class SendWindow {

    private static final int SEG_SIZE = 100;   // MSS（字节）
    private static final int TIMEOUT = 1000;

    /* ===== 拥塞控制参数（单位：字节） ===== */
    private int cwnd = SEG_SIZE;               // 初始 1 MSS
    private int ssthresh = 16 * SEG_SIZE;

    /* ===== SR 窗口指针（字节序号） ===== */
    private int sendBase = 1;
    private int nextSeq = 1;
    /* ===== Reno 新增状态 ===== */
    private int dupAckCount = 0;
    private boolean inFastRecovery = false;

    private UDT_Timer timer = null;

    /* SR 发送窗口 */
    private final Map<Integer, SendEntry> window = new HashMap<>();
    private final Client client;

    public SendWindow(Client client) {
        this.client = client;
    }

    /* ===================== 窗口是否可用 ===================== */
    public boolean isWindowAvailable() {
        return nextSeq < sendBase + cwnd;
    }

    /* ===================== 放入窗口并发送 ===================== */
    public void putPacket(TCP_PACKET pkt) {
        if (pkt == null || !isWindowAvailable()) {
            return;
        }

        int seq = pkt.getTcpH().getTh_seq();
        if (window.containsKey(seq)) {
            return;
        }

        SendEntry entry = new SendEntry(pkt);
        window.put(seq, entry);

        if (sendBase == nextSeq) {
            startTimer();     // 只有 base 包启动定时器
        }

        nextSeq += SEG_SIZE;
    }

    /* ===================== ACK 处理（SR） ===================== */
    public void onAck(int ack) {
        if (ack < sendBase - SEG_SIZE) {
            return;
        }
        if (ack == sendBase - SEG_SIZE) {
            dupAckCount++;
            System.out.println("[DupACK] count = " + dupAckCount);
            /* ===== 快重传触发 ===== */
            if (dupAckCount >= 3 && !inFastRecovery) {
                System.out.println("[Fast Retransmit] at seq = " + sendBase);
                /* Reno 拥塞控制 */
                ssthresh = Math.max((cwnd / 2 / SEG_SIZE) * SEG_SIZE, SEG_SIZE);
                cwnd = ssthresh;
                inFastRecovery = true;
                /* 立即重传 sendBase */
                SendEntry entry = window.get(sendBase);
                if (entry != null) {
                    client.send(entry.packet);
                }
            }
            /* 快恢复期间：每个额外 DupACK 膨胀 cwnd */
            else if (inFastRecovery) {
                cwnd += SEG_SIZE ;
                System.out.println("[Fast Recovery] cwnd inflate = " + cwnd / SEG_SIZE);
            }
            return;
        }
        // 收到新 ACK
        dupAckCount = 0;
        int seq = sendBase;
        while (seq <= ack) {
            SendEntry e = window.get(seq);
            if (e != null) {
                e.acked = true;
            }
            seq += SEG_SIZE;
        }
        /* ===== 如果在快恢复，退出 ===== */
        if (inFastRecovery) {
            cwnd = ssthresh;
            inFastRecovery = false;
            System.out.println("[Exit Fast Recovery] cwnd = " + cwnd / SEG_SIZE);
        } else {
            /* 正常 Reno：慢启动 / 拥塞避免 */
            if (cwnd < ssthresh) {
                cwnd += SEG_SIZE;
                System.out.println("[Slow Start] cwnd = " + cwnd / SEG_SIZE);
            } else {
                cwnd += (SEG_SIZE * SEG_SIZE) / cwnd;
                System.out.println("[Congestion Avoidance] cwnd = " + cwnd / SEG_SIZE);
            }
        }

        advanceSendBase();
    }


    /* ===================== 公共方法：推进 SendBase（滑动窗口核心逻辑） ===================== */
    private void advanceSendBase() {
        boolean baseMoved = false;

        // 循环检查并推进 sendBase：只要 sendBase 对应的包已被确认，就滑动窗口
        while (window.containsKey(sendBase) && window.get(sendBase).acked) {
            window.remove(sendBase);  // 移除已确认的包，释放窗口资源
            sendBase += SEG_SIZE;     // 推进窗口基址（移动窗口）
            baseMoved = true;
            System.out.println("[Window Advanced] New sendBase = " + sendBase + ",nextSeq = " + nextSeq);
        }

        // 仅在窗口基址移动时处理定时器
        if (baseMoved) {
            stopTimer();
            // 窗口不为空时，为新的 sendBase 启动定时器
            if (!window.isEmpty()) {
                startTimer();
            }
        }
    }

    /* ===================== 定时器控制 ===================== */
    private void startTimer() {
        stopTimer();
        timer = new UDT_Timer();
        // 传入当前 sendBase 对应的包到定时器任务（修复原 null 问题）
        SendEntry entry = window.get(sendBase);
        TCP_PACKET basePkt = entry != null ? entry.packet : null;
        timer.schedule(new RetransTask(basePkt), TIMEOUT);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /* ===================== 单个分组状态 ===================== */
    private static class SendEntry {
        TCP_PACKET packet;
        boolean acked = false;

        SendEntry(TCP_PACKET packet) {
            this.packet = packet;
        }
    }

    /* ===================== 超时重传（修复：重传后滑动窗口） ===================== */
    private class RetransTask extends UDT_RetransTask {

        public RetransTask(TCP_PACKET pkt) {
            super(client, pkt);  // 传入有效数据包，修复原 null 问题
        }

        @Override
        public void run() {
            System.out.println("Timeout at sendBase = " + sendBase);

            /* ===== TCP 拥塞控制：超时 ===== */
            int half = cwnd / 2;
            ssthresh = Math.max(half, SEG_SIZE);
            cwnd = SEG_SIZE;
            System.out.println("[Timeout Congestion Control] ssthresh = " + ssthresh / SEG_SIZE + ", cwnd = " + cwnd / SEG_SIZE);

            /* ===== SR：重传 sendBase 对应的包 ===== */
            SendEntry entry = window.get(sendBase);
            if (entry != null) {
                client.send(entry.packet);
                System.out.println("[Retransmit] Sent sendBase packet: " + sendBase);
            }

            advanceSendBase();  // 调用公共窗口推进方法，尝试滑动窗口

            /* ===== 仅在窗口不为空时重启定时器（避免无效定时器） ===== */
            if (!window.isEmpty()) {
                startTimer();
            }
        }
    }
}