package com.ouc.tcp.test;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.message.TCP_PACKET;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class ReceiveWindow {

    private static final int SEG_SIZE = 100;
    private static final int WINDOW_SIZE = 8 * SEG_SIZE;

    private int rcvBase = 1;

    private final Map<Integer, TCP_PACKET> buffer = new HashMap<>();
    private final Queue<int[]> dataQueue = new LinkedList<>();

    /* ===================== 接收 SR 分组 ===================== */

    public boolean onPacket(TCP_PACKET pkt) {

        int seq = pkt.getTcpH().getTh_seq();

        /* ---------- 窗口内 ---------- */
        if (seq >= rcvBase && seq < rcvBase + WINDOW_SIZE) {
            if (!buffer.containsKey(seq)) {
                buffer.put(seq, pkt);
            }

            if(seq == rcvBase) {
                slideWindow();
                return true;
            }
            return false;
        }

        /* ---------- 已接收过（窗口左侧） ---------- */
        if (seq < rcvBase) {
            return true;
        }

        /* ---------- 窗口右侧 ---------- */
        return false;
    }

    /* ===================== 滑动窗口并交付 ===================== */

    private void slideWindow() {
        while (buffer.containsKey(rcvBase)) {
            TCP_PACKET pkt = buffer.remove(rcvBase);
            dataQueue.add(pkt.getTcpS().getData());
            rcvBase += SEG_SIZE;
        }
        if (dataQueue.size() == 20 || rcvBase == 100001)
            deliver_data();
    }


    private void deliver_data() {
        //检查dataQueue，将数据写入文件
        File fw = new File("recvData.txt");
        BufferedWriter writer;

        try {
            writer = new BufferedWriter(new FileWriter(fw, true));

            //循环检查data队列中是否有新交付数据
            while (!dataQueue.isEmpty()) {
                int[] data = dataQueue.poll();

                //将数据写入文件
                for (int datum : data) {
                    writer.write(datum + "\n");
                }

                writer.flush();        //清空输出缓存
            }
            writer.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
