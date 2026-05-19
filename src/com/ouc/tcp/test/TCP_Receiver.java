package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;

public class TCP_Receiver extends TCP_Receiver_ADT {
    private static final int SEG_SIZE = 100;

    private final TcpLabConfig config = TcpLabConfig.get();

    private TCP_PACKET ackPack;    // 回复的 ACK 报文段
    int sequence = 1;              // 用于记录当前待接收的包序号
    int lastSeq = 1;
    ReceiveWindow receiveWindow;

    /*构造函数*/
    public TCP_Receiver() {
        super();
        super.initTCP_Receiver(this);
        receiveWindow = new ReceiveWindow();
    }

    @Override
    public void rdt_recv(TCP_PACKET recvPack) {
        boolean checksumOK = CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum();

        if (checksumOK) {
            receiveWindow.onPacket(recvPack);
            sequence++;
        } else {
            System.out.println("Recieve Computed: " + CheckSum.computeChkSum(recvPack));
            System.out.println("Recieved Packet: " + recvPack.getTcpH().getTh_sum());
            System.out.println("Problem: Packet Number: " + recvPack.getTcpH().getTh_seq()
                    + " + InnerSeq: " + sequence);
        }

        /*
         * Reno / NewReno 关键：
         * 始终回复累计 ACK，而不是回复当前收到的包号。
         *
         * - 收到按序包：rcvBase 向前滑动，ACK 变大；
         * - 收到乱序包：rcvBase 不动，重复回复上一次累计 ACK；
         * - 收到旧包：rcvBase 不动，仍回复当前最新累计 ACK；
         * - 收到错误包：rcvBase 不动，仍回复当前最新累计 ACK。
         */
        lastSeq = receiveWindow.getRcvBase() - SEG_SIZE;
        tcpH.setTh_ack(lastSeq);

        ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
        tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));

        reply(ackPack);
        System.out.println();
    }

    @Override
    public void deliver_data() {
        File fw = new File("recvData.txt");
        BufferedWriter writer;

        try {
            writer = new BufferedWriter(new FileWriter(fw, true));

            while (!dataQueue.isEmpty()) {
                int[] data = dataQueue.poll();

                for (int datum : data) {
                    writer.write(datum + "\n");
                }

                writer.flush();
            }
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void reply(TCP_PACKET replyPack) {
        /*
         * ACK 差错控制从配置读取。
         *
         * 建议测试 NewReno 时：
         * tcp.ack.eflag=0
         *
         * 等确认 NewReno 的 DupACK / Partial ACK / Full ACK 行为正常后，
         * 再逐步打开 ACK 丢包、延迟或出错。
         */
        replyPack.getTcpH().setTh_eflag((byte) config.getAckEflag());
        client.send(replyPack);
    }
}
