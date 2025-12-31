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

    // 发送缓存：按序号索引，存储所有未确认分组
    private final Map<Integer, TCP_PACKET> buffer = new HashMap<>();

    private UDT_Timer timer = new UDT_Timer();
    private final Client client;

    public SendWindow(Client client) {
        this.client = client;
    }

    /* ===================== 发送侧接口 ===================== */

    public boolean isWindowAvailable() {
        return nextSeq < sendBase + WINDOW_SIZE;
    }

    public boolean putPacket(TCP_PACKET pkt) {
        if (pkt == null || !isWindowAvailable()) {
            return false;
        }

        int seq = pkt.getTcpH().getTh_seq();

        if (seq != nextSeq) {
            return false;
        }

        buffer.put(seq, pkt);

        if (sendBase == nextSeq) {
            startTimer();   // 仅在窗口从空变非空时启动
        }

        nextSeq += SEG_SIZE;
        return true;
    }

    /* ===================== ACK 处理 ===================== */

    public void onAck(int ack) {

        if (ack <= sendBase || ack > nextSeq) {
            return;
        }

        // 删除所有 seq < ack 的分组
        for (int seq = sendBase; seq < ack; seq += SEG_SIZE) {
            buffer.remove(seq);
        }

        sendBase = ack;

        if (sendBase >= nextSeq) {
            timer.cancel();   // 窗口空，停表
            nextSeq = ack;
        } else {
            startTimer();     // 窗口仍有未确认数据
        }
    }


    /* ===================== 超时重传 ===================== */

    public void retransmitAllUnAckedPackets() {
        //解决程序结束后仍然发送的问题（屎，但是管用）
        if(nextSeq == 100001)
            return;
        if(sendBase == nextSeq)
            return;
        System.out.println("Timer fired, base=" + sendBase + ", next=" + nextSeq);
        System.out.println("Timeout, resend from byte: " + sendBase);

        for (int seq = sendBase; seq < nextSeq; seq += SEG_SIZE) {
            TCP_PACKET pkt = buffer.get(seq);
            if (pkt != null) {
                client.send(pkt);
            }
        }
        startTimer();
    }


    private void startTimer() {
        timer.cancel();
        timer = new UDT_Timer();
        timer.schedule(new RetransTask(), TIMEOUT);
    }

    /* ===================== 定时器任务 ===================== */

    private class RetransTask extends UDT_RetransTask {
        public RetransTask() {
            super(client, null);
        }

        @Override
        public void run() {
            retransmitAllUnAckedPackets();
        }
    }
}
