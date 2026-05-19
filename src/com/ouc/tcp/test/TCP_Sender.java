package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;

public class TCP_Sender extends TCP_Sender_ADT {

    private final TcpLabConfig config = TcpLabConfig.get();

    private TCP_PACKET tcpPack;    // 待发送的 TCP 数据报
    private volatile int flag = 1;
    private final SendWindow sendWindow;

    /*构造函数*/
    public TCP_Sender() {
        super();
        super.initTCP_Sender(this);
        sendWindow = new SendWindow(client);
    }

    @Override
    public void rdt_send(int dataIndex, int[] appData) {
        if (!sendWindow.isWindowAvailable()) {
            System.out.println("Window is not available");
            flag = 0;
        }

        while (flag == 0) {
            // 等待 ACK 或快速恢复结束后窗口重新可用
        }

        /*
         * 包序号设置为字节流号。
         * SEG_SIZE 为 100 时，第 i 个包对应 i*100+1。
         */
        tcpH.setTh_seq(dataIndex * appData.length + 1);
        tcpH.setTh_eflag((byte) config.getDataEflag());

        tcpS.setData(appData);
        tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);

        tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
        tcpPack.setTcpH(tcpH);

        try {
            /*
             * 先放入发送窗口，窗口中保存的是待确认 / 待重传版本。
             * 此时 eFlag 已经从配置中读取并设置完毕。
             */
            sendWindow.putPacket(tcpPack.clone());
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }

        udt_send(tcpPack);
    }

    @Override
    public void udt_send(TCP_PACKET stcpPack) {
        /*
         * DATA 差错控制从配置读取。
         *
         * 建议测试 NewReno 时：
         * tcp.data.eflag=2
         *
         * 即先只模拟 DATA 丢包，避免延迟旧包或错包干扰快速恢复判断。
         */
        stcpPack.getTcpH().setTh_eflag((byte) config.getDataEflag());
        client.send(stcpPack);
    }

    @Override
    public void waitACK() {
        if (!ackQueue.isEmpty()) {
            int currentAck = ackQueue.poll();
            if (currentAck == tcpPack.getTcpH().getTh_seq()) {
                System.out.println("Clear: " + tcpPack.getTcpH().getTh_seq());
                flag = 1;
            } else {
                flag = 0;
            }
        }
    }

    @Override
    public void recv(TCP_PACKET recvPack) {
        if (recvPack.getTcpH().getTh_sum() == CheckSum.computeChkSum(recvPack)) {
            int thAck = recvPack.getTcpH().getTh_ack();
            System.out.println("Receive ACK Number： " + thAck);
            System.out.println();

            sendWindow.onAck(thAck);

            if (sendWindow.isWindowAvailable()) {
                flag = 1;
            }
        }
    }
}
