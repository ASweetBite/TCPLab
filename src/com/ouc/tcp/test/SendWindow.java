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

    /* ===== 配置 ===== */
    private final TcpLabConfig config = TcpLabConfig.get();
    private final int timeout = config.getTimeout();

    /* ===== 拥塞控制参数（单位：字节） ===== */
    private int cwnd = SEG_SIZE;               // 初始 1 MSS
    private int ssthresh = 16 * SEG_SIZE;

    /* ===== 窗口指针（字节序号） ===== */
    private int sendBase = 1;
    private int nextSeq = 1;
    private int dupAckCount = 0;
    private boolean inFastRecovery = false;

    /*
     * NewReno 关键变量：
     * 进入快速恢复时，记录当时已经发送出去的最高序号。
     * 后续 ACK 若小于 recoverPoint，则认为是 partial ACK，不退出快速恢复。
     */
    private int recoverPoint = 0;

    private UDT_Timer timer = null;
    private final Map<Integer, SendEntry> window = new HashMap<>();
    private final Client client;

    // ===== 日志记录器 =====
    private PrintWriter logWriter;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");

    public SendWindow(Client client) {
        this.client = client;
        try {
            logWriter = new PrintWriter(new FileWriter(config.getLogFile(), false));
            logState("INIT", "Window Initialized, protocol: " + config.getProtocol()
                    + ", dataEFlag: " + config.getDataEflag()
                    + ", ackEFlag: " + config.getAckEflag()
                    + ", retransEFlag: " + config.getRetransEflag()
                    + ", freezeInFastRecovery: " + config.freezeInFastRecovery());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 核心日志记录方法
     * 格式：时间 事件 base nextSeq cwnd ssthresh 状态 附加信息
     */
    private void logState(String event, String info) {
        String timestamp = sdf.format(new Date());
        String stateStr = inFastRecovery ? "FastRecovery" : (cwnd < ssthresh ? "SlowStart" : "CongAvoid");

        System.out.println("[" + event + "] " + info
                + " | cwnd:" + cwnd / SEG_SIZE
                + " ssthresh:" + ssthresh / SEG_SIZE
                + " protocol:" + config.getProtocol());

        if (logWriter != null) {
            logWriter.printf("%s\tEVENT:%-18s\tbase:%-8d\tnext:%-8d\tcwnd:%-6d\tssthresh:%-6d\tstate:%-12s\tinfo:%s\n",
                    timestamp, event, sendBase, nextSeq, cwnd, ssthresh, stateStr, info);
            logWriter.flush();
        }
    }

    /**
     * 判断发送窗口是否允许继续接收上层新数据。
     *
     * 为了让课程实验更清楚地展示 NewReno 的 partial ACK 行为，
     * 默认在快速恢复阶段暂停发送新数据，只处理当前恢复窗口内的包。
     *
     * 如果想更接近真实 TCP，可以在配置中设置：
     * tcp.freezeInFastRecovery=false
     */
    public boolean isWindowAvailable() {
        if (config.freezeInFastRecovery() && inFastRecovery) {
            return false;
        }
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
        /*
         * 过旧 ACK：比当前累计确认号还小，直接忽略。
         * 当前累计确认号是 sendBase - SEG_SIZE。
         */
        if (ack < sendBase - SEG_SIZE) {
            logState("OLD_ACK", "AckNum: " + ack + ", lastAcked: " + (sendBase - SEG_SIZE));
            return;
        }

        /*
         * 重复 ACK：ACK 等于当前累计确认号。
         */
        if (ack == sendBase - SEG_SIZE) {
            dupAckCount++;
            logState("DUP_ACK", "AckNum: " + ack + ", count: " + dupAckCount);

            if (dupAckCount >= 3 && !inFastRecovery) {
                enterFastRecovery();
            } else if (inFastRecovery) {
                /*
                 * Reno / NewReno 快速恢复中的额外 DupACK：
                 * 只膨胀 cwnd，不重复重传 sendBase。
                 * 是否继续发送新数据由 isWindowAvailable() 和配置控制。
                 */
                cwnd += SEG_SIZE;
                logState("FR_INFLATE", "Extra DupACK, cwnd += MSS");
            }
            return;
        }

        /*
         * 新 ACK：标记累计确认范围。
         */
        logState("NEW_ACK", "AckNum: " + ack);
        markAckedUpTo(ack);

        if (inFastRecovery) {
            handleAckInFastRecovery(ack);
        } else {
            dupAckCount = 0;
            updateCwndNormally();
            advanceSendBase();
        }
    }

    private void enterFastRecovery() {
        ssthresh = Math.max((cwnd / 2 / SEG_SIZE) * SEG_SIZE, SEG_SIZE);
        cwnd = ssthresh + 3 * SEG_SIZE;
        inFastRecovery = true;

        // NewReno：记录进入快速恢复时已经发送出去的最高序号。
        recoverPoint = nextSeq - SEG_SIZE;

        logState("ENTER_FR", "recoverPoint: " + recoverPoint);
        retransmitSendBase("FAST_RETRANS");
    }

    private void handleAckInFastRecovery(int ack) {
        /*
         * 先推进 sendBase。
         * 如果 ACK 只确认了一部分数据，sendBase 会停在下一个未确认包。
         */
        advanceSendBase();

        if (config.isNewReno() && ack < recoverPoint) {
            /*
             * NewReno partial ACK：
             * ACK 没有覆盖 recoverPoint，说明进入快速恢复时已经发出的数据中
             * 仍有后续报文段丢失。此时不退出快速恢复，而是立即重传新的 sendBase。
             */
            logState("PARTIAL_ACK", "AckNum: " + ack
                    + ", recoverPoint: " + recoverPoint
                    + ", retransmit new base: " + sendBase);

            retransmitSendBase("NEWRENO_RETRANS");

            // 保持快速恢复状态。
            cwnd = Math.max(ssthresh + 3 * SEG_SIZE, SEG_SIZE);

            restartTimerIfNeeded();
            return;
        }

        /*
         * Reno：收到任意 New ACK 就退出快速恢复。
         * NewReno：只有 full ACK，即 ack >= recoverPoint，才退出快速恢复。
         */
        cwnd = ssthresh;
        inFastRecovery = false;
        dupAckCount = 0;
        recoverPoint = 0;

        if (config.isNewReno()) {
            logState("EXIT_FR", "Full ACK, Exit Fast Recovery");
        } else {
            logState("EXIT_FR", "Reno New ACK, Exit Fast Recovery");
        }

        restartTimerIfNeeded();
    }

    private void updateCwndNormally() {
        if (cwnd < ssthresh) {
            cwnd += SEG_SIZE; // 慢启动
            logState("CWND_UPDATE", "Slow Start cwnd += MSS");
        } else {
            int delta = (SEG_SIZE * SEG_SIZE) / cwnd;
            if (delta <= 0) {
                delta = 1;
            }
            cwnd += delta; // 拥塞避免
            logState("CWND_UPDATE", "Congestion Avoidance cwnd += MSS*MSS/cwnd");
        }
    }

    private void markAckedUpTo(int ack) {
        int seq = sendBase;
        while (seq <= ack) {
            SendEntry e = window.get(seq);
            if (e != null) {
                e.acked = true;
            }
            seq += SEG_SIZE;
        }
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
            restartTimerIfNeeded();
        }
    }

    private void retransmitSendBase(String eventName) {
        SendEntry entry = window.get(sendBase);
        if (entry != null) {
            entry.packet.getTcpH().setTh_eflag((byte) config.getRetransEflag());
            client.send(entry.packet);
            logState(eventName, "Retransmit seq: " + sendBase);
        } else {
            logState(eventName, "No packet found at sendBase: " + sendBase);
        }
    }

    private void restartTimerIfNeeded() {
        stopTimer();
        if (!window.isEmpty()) {
            startTimer();
        }
    }

    private void startTimer() {
        stopTimer();
        timer = new UDT_Timer();
        SendEntry entry = window.get(sendBase);
        TCP_PACKET basePkt = entry != null ? entry.packet : null;
        timer.schedule(new RetransTask(basePkt), timeout);
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

        SendEntry(TCP_PACKET packet) {
            this.packet = packet;
        }
    }

    private class RetransTask extends UDT_RetransTask {
        public RetransTask(TCP_PACKET pkt) {
            super(client, pkt);
        }

        @Override
        public void run() {
            logState("TIMEOUT", "Timeout at base: " + sendBase);

            ssthresh = Math.max((cwnd / 2 / SEG_SIZE) * SEG_SIZE, SEG_SIZE);
            cwnd = SEG_SIZE;
            inFastRecovery = false;
            dupAckCount = 0;
            recoverPoint = 0;

            retransmitSendBase("RETRANSMIT");

            if (!window.isEmpty()) {
                startTimer();
            }
        }
    }
}
