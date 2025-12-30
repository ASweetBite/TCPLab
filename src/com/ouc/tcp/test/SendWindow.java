package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;

import java.util.HashMap;
import java.util.Map;

public class SendWindow {
    private final int WINDOW_SIZE = 5;
    private int sendBase = 1;
    private int nextSeq = 1;

    private Map<Integer, TCP_PACKET> buffer = new HashMap<>();

    private UDT_Timer timer = new UDT_Timer();
    private static final int TIMEOUT = 300;

    private Client client;

    SendWindow(Client client) {
        this.client = client;
    }

    public boolean IsSendable() {
        return nextSeq < sendBase + WINDOW_SIZE;
    }

    public void sendPacket(TCP_PACKET pkt) {

        int seq = pkt.getTcpH().getTh_seq();

        if (!IsSendable())
            return;

        buffer.put(seq, pkt);
        client.send(pkt);

        if (sendBase == seq) {
            startTimer();
        }

        nextSeq++;
    }

    private void startTimer() {
        timer.cancel();
        timer = new UDT_Timer();
        timer.schedule(new UDT_RetransTask(client, buffer.get(sendBase)), TIMEOUT);
    }


    public void onAck(int ack) {

        for (int seq = sendBase; seq <= ack; seq++) {
            buffer.remove(seq);
        }

        sendBase = ack + 1;

        if (sendBase == nextSeq) {
            timer.cancel();
        } else {
            startTimer();
        }
    }

    public int getSendBase() {
        return sendBase;
    }

}
