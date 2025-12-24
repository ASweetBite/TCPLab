package com.ouc.tcp.test;

import java.util.zip.CRC32;

import com.ouc.tcp.message.TCP_HEADER;
import com.ouc.tcp.message.TCP_PACKET;

public class CheckSum {
    /*计算TCP报文段校验和：只需校验TCP首部中的seq、ack以及TCP数据字段*/
    public static short computeChkSum(TCP_PACKET tcpPack) {
        int sum = 0;
        // seq
        sum = addInt(sum, tcpPack.getTcpH().getTh_seq());
        // ack
        sum = addInt(sum, tcpPack.getTcpH().getTh_ack());
        // data
        int[] data = tcpPack.getTcpS().getData();
        if (data != null) {
            for (int d : data) {
                sum = addInt(sum, d);
            }
        }
        // 最终取反
        return (short) ~fold(sum);
    }

    /**
     * 将一个 32 位 int 拆成两个 16 位累加
     */
    private static int addInt(int sum, int value) {
        sum += (value >>> 16) & 0xFFFF;
        sum += value & 0xFFFF;
        return fold(sum);
    }

    /**
     * 折叠 16 位进位
     */
    private static int fold(int sum) {
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        return sum;
    }
}
