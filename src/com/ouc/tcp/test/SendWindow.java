package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

import java.util.HashMap;
import java.util.Map;

public class SendWindow {

    private static final int SEG_SIZE = 100;
    private static final int TIMEOUT = 300;

    // ===== 拥塞控制参数 =====
    private int cwnd = SEG_SIZE;              // 初始 1 MSS
    private int ssthresh = 16 * SEG_SIZE;      // 初始阈值

    private int sendBase = 1;
    private int nextSeq = 1;
    private UDT_Timer timer = null;


    // SR 发送窗口
    private final Map<Integer, SendEntry> window = new HashMap<>();

    private final Client client;

    public SendWindow(Client client) {
        this.client = client;
    }

    /* ===================== 窗口判断 ===================== */

    public boolean isWindowAvailable() {
        return nextSeq < sendBase + cwnd;
    }

    /* ===================== 放入窗口（不发送） ===================== */

    public void putPacket(TCP_PACKET pkt) {
        if (pkt == null || !isWindowAvailable()) {
            return;
        }

        int seq = pkt.getTcpH().getTh_seq();
        if (seq != nextSeq) {
            return;
        }

        SendEntry entry = new SendEntry(pkt);
        window.put(seq, entry);

        if (sendBase == nextSeq) {
            startTimer();
        }

        nextSeq += SEG_SIZE;
    }

    /* ===================== ACK 处理（SR） ===================== */

    public void onAck(int ack) {

        SendEntry entry = window.get(ack);
        if (entry == null || entry.acked) {
            return;
        }

        entry.acked = true;

        if (cwnd < ssthresh) {
            // 慢启动：每个 ACK 增长 1 MSS
            cwnd *= 2;
            System.out.println("[Slow Start] cwnd = " + cwnd/100);
        } else if (cwnd > 30) {
            cwnd = 1;
        } else {
            // 拥塞避免
            cwnd += SEG_SIZE;
            System.out.println("[Congestion Avoidance] cwnd = " + cwnd/100);
        }

        boolean baseMoved = false;

        while (window.containsKey(sendBase) && window.get(sendBase).acked) {
            window.remove(sendBase);
            sendBase += SEG_SIZE;
            baseMoved = true;
        }

        if (baseMoved) {
            stopTimer();
            if (!window.isEmpty()) {
                startTimer();  // 新的 sendBase
            }
        }
    }

    private void startTimer() {
        stopTimer();
        timer = new UDT_Timer();
        timer.schedule(new RetransTask(), TIMEOUT);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }


    /* ===================== 单个分组状态 ===================== */

    private class SendEntry {
        TCP_PACKET packet;
        boolean acked;

        SendEntry(TCP_PACKET packet) {
            this.packet = packet;
            this.acked = false;
        }

    }

    /* ===================== 超时任务（仅该分组） ===================== */

    private class RetransTask extends UDT_RetransTask {

        public RetransTask() {
            super(client, null);
        }

        @Override
        public void run() {

            System.out.println("Timeout at sendBase = " + sendBase);

            /* ===== TCP 拥塞控制 ===== */
            ssthresh = Math.max(cwnd / 200, 1) * SEG_SIZE;
            cwnd = SEG_SIZE;

            SendEntry entry = window.get(sendBase);
            if (entry != null) {
                client.send(entry.packet);
            }

            startTimer(); // 继续监控新的 sendBase
        }

    }
}
