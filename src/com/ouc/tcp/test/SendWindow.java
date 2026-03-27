package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class SendWindow {

    private static final int SEG_SIZE = 100;   // MSS（字节）
    private static final int TIMEOUT = 1000;

    /* ===== 拥塞控制参数（单位：字节） ===== */
    private int cwnd = SEG_SIZE;               // 初始 1 MSS
    private int ssthresh = 16 * SEG_SIZE;

    /* ===== 窗口指针（字节序号） ===== */
    private int sendBase = 1;
    private int nextSeq = 1;
    private int dupAckCount = 0;
    private boolean inFastRecovery = false;

    private UDT_Timer timer = null;
    private final Map<Integer, SendEntry> window = new HashMap<>();
    private final Client client;

    // ===== 日志记录器 =====
    private PrintWriter logWriter;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");

    public SendWindow(Client client) {
        this.client = client;
        try {
            // 日志保存在项目根目录
            logWriter = new PrintWriter(new FileWriter("tcp_reno.log", false));
            logState("INIT", "Window Initialized");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 核心日志记录方法
     * 格式：时间 事件 seq base nextSeq cwnd ssthresh 状态 附加信息
     */
    private void logState(String event, String info) {
        String timestamp = sdf.format(new Date());
        String stateStr = inFastRecovery ? "FastRecovery" : (cwnd < ssthresh ? "SlowStart" : "CongAvoid");

        // 打印到控制台（保留你原有的习惯）
        System.out.println("[" + event + "] " + info + " | cwnd:" + cwnd/SEG_SIZE + " ssthresh:" + ssthresh/SEG_SIZE);

        // 写入文件 (使用制表符或固定格式便于后续解析)
        if (logWriter != null) {
            logWriter.printf("%s\tEVENT:%-15s\tbase:%-8d\tnext:%-8d\tcwnd:%-6d\tssthresh:%-6d\tstate:%-12s\tinfo:%s\n",
                    timestamp, event, sendBase, nextSeq, cwnd, ssthresh, stateStr, info);
            logWriter.flush();
        }
    }

    public boolean isWindowAvailable() {
        return nextSeq < sendBase + cwnd;
    }

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
            startTimer();
        }

        nextSeq += SEG_SIZE;
        logState("SEND", "DATA_seq: " + seq);
    }

    public void onAck(int ack) {
        if (ack < sendBase - SEG_SIZE) {
            return;
        }

        // 处理重复 ACK (DupACK)
        if (ack == sendBase - SEG_SIZE) {
            dupAckCount++;
            logState("DUP_ACK", "AckNum: " + ack + " count: " + dupAckCount);

            if (dupAckCount >= 3 && !inFastRecovery) {
                // 进入快重传/快恢复
                ssthresh = Math.max((cwnd / 2 / SEG_SIZE) * SEG_SIZE, SEG_SIZE);
                cwnd = ssthresh + 3 * SEG_SIZE; // Reno 标准：进入快恢复时 cwnd 膨胀
                inFastRecovery = true;

                logState("FAST_RETRANS", "Retransmit seq: " + sendBase);

                SendEntry entry = window.get(sendBase);
                if (entry != null) {
                    client.send(entry.packet);
                }
            }
            else if (inFastRecovery) {
                cwnd += SEG_SIZE; // 每收到一个多余的 DupACK，窗口膨胀 1 MSS
                logState("FR_INFLATE", "Fast Recovery cwnd inflate");

                SendEntry entry = window.get(sendBase);
                if (entry != null) {
                    client.send(entry.packet);
                }
            }
            return;
        }

        // 收到新的 ACK (New ACK)
        logState("NEW_ACK", "AckNum: " + ack);
        dupAckCount = 0;

        // 累计确认逻辑
        int seq = sendBase;
        while (seq <= ack) {
            SendEntry e = window.get(seq);
            if (e != null) {
                e.acked = true;
            }
            seq += SEG_SIZE;
        }

        if (inFastRecovery) {
            // 收到新确认，退出快恢复
            cwnd = ssthresh;
            inFastRecovery = false;
            logState("EXIT_FR", "Exit Fast Recovery");
        } else {
            // 正常拥塞控制更新
            if (cwnd < ssthresh) {
                cwnd += SEG_SIZE; // 慢启动
            } else {
                cwnd += (SEG_SIZE * SEG_SIZE) / cwnd; // 拥塞避免
            }
        }

        advanceSendBase();
    }

    private void advanceSendBase() {
        boolean baseMoved = false;
        int oldBase = sendBase;

        while (window.containsKey(sendBase) && window.get(sendBase).acked) {
            window.remove(sendBase);
            sendBase += SEG_SIZE;
            baseMoved = true;
        }

        if (baseMoved) {
            logState("WINDOW_SLIDE", "Base: " + oldBase + " -> " + sendBase);
            stopTimer();
            if (!window.isEmpty()) {
                startTimer();
            }
        }
    }

    private void startTimer() {
        stopTimer();
        timer = new UDT_Timer();
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

    private static class SendEntry {
        TCP_PACKET packet;
        boolean acked = false;
        SendEntry(TCP_PACKET packet) { this.packet = packet; }
    }

    private class RetransTask extends UDT_RetransTask {
        public RetransTask(TCP_PACKET pkt) { super(client, pkt); }

        @Override
        public void run() {
            logState("TIMEOUT", "Timeout at base: " + sendBase);

            ssthresh = Math.max((cwnd / 2 / SEG_SIZE) * SEG_SIZE, SEG_SIZE);
            cwnd = SEG_SIZE; // 超时回到 1 MSS
            inFastRecovery = false;
            dupAckCount = 0;

            SendEntry entry = window.get(sendBase);
            if (entry != null) {
                client.send(entry.packet);
                logState("RETRANSMIT", "Resend Base: " + sendBase);
            }

            // 注意：超时不应该立刻 advanceSendBase，而是重传后再等待 ACK
            // 除非逻辑要求，否则通常超时只重传不滑动
            if (!window.isEmpty()) {
                startTimer();
            }
        }
    }
}