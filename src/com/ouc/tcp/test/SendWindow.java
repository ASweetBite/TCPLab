package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.TCP_PACKET;

import java.util.HashMap;
import java.util.Map;

public class SendWindow {

    private static final int SEG_SIZE = 100;
    private static final int WINDOW_SIZE = 8 * SEG_SIZE;
    private static final int TIMEOUT = 300;

    private int sendBase = 1;
    private int nextSeq = 1;

    // SR 发送窗口
    private final Map<Integer, SendEntry> window = new HashMap<>();

    private final Client client;

    public SendWindow(Client client) {
        this.client = client;
    }

    /* ===================== 窗口判断 ===================== */

    public boolean isWindowAvailable() {
        return nextSeq < sendBase + WINDOW_SIZE;
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

        entry.startTimer();

        nextSeq += SEG_SIZE;
    }

    /* ===================== ACK 处理（SR） ===================== */

    public void onAck(int ack) {

        SendEntry entry = window.get(ack);
        if (entry == null || entry.acked) {
            return;
        }

        entry.acked = true;
        entry.stopTimer();

        // SR：只能滑动到第一个未确认分组
        while (window.containsKey(sendBase) && window.get(sendBase).acked) {
            window.remove(sendBase);
            sendBase += SEG_SIZE;
        }
    }

    /* ===================== 单个分组状态 ===================== */

    private class SendEntry {
        TCP_PACKET packet;
        boolean acked;
        UDT_Timer timer;

        SendEntry(TCP_PACKET packet) {
            this.packet = packet;
            this.acked = false;
        }

        void startTimer() {
            timer = new UDT_Timer();
            timer.schedule(
                    new RetransTask(packet),
                    TIMEOUT
            );
        }

        void stopTimer() {
            if (timer != null) {
                timer.cancel();
            }
        }
    }

    /* ===================== 超时任务（仅该分组） ===================== */

    private class RetransTask extends UDT_RetransTask {

        private final TCP_PACKET packet;

        public RetransTask(TCP_PACKET packet) {
            super(client, packet);
            this.packet = packet;
        }

        @Override
        public void run() {
            int seq = packet.getTcpH().getTh_seq();
            SendEntry entry = window.get(seq);

            if (entry == null || entry.acked) {
                return;
            }

            System.out.println("Timeout, resend seq = " + seq);

            // ⚠️ 只重传该包
            client.send(packet);

            // 重启该包自己的定时器
            entry.startTimer();
        }
    }
}
