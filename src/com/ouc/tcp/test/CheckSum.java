package com.ouc.tcp.test;

import java.util.zip.CRC32;

import com.ouc.tcp.message.TCP_HEADER;
import com.ouc.tcp.message.TCP_PACKET;

public class CheckSum {

    /*计算TCP报文段校验和：只需校验TCP首部中的seq、ack和sum，以及TCP数据字段*/
    public static short computeChkSum(TCP_PACKET tcpPack) {
        int checkSum = 0;

        // 1. 处理TCP首部的seq字段（返回int，32位，拆分为两个16位累加）
        int seq = tcpPack.getTcpH().getTh_seq();
        // 高16位
        checkSum += (seq >> 16) & 0xFFFF;
        checkSum = handleCarry(checkSum);
        // 低16位
        checkSum += seq & 0xFFFF;
        checkSum = handleCarry(checkSum);

        // 2. 处理TCP首部的ack字段（返回int，32位，拆分为两个16位累加）
        int ack = tcpPack.getTcpH().getTh_ack();
        // 高16位
        checkSum += (ack >> 16) & 0xFFFF;
        checkSum = handleCarry(checkSum);
        // 低16位
        checkSum += ack & 0xFFFF;
        checkSum = handleCarry(checkSum);

        // 3. 处理sum字段：计算校验和前需将sum置0，此处直接累加0
        // 调用前需执行：tcpPack.getTcpH().setTh_sum((short)0)
        checkSum += 0;
        checkSum = handleCarry(checkSum);

        // 4. 处理TCP数据字段（返回int[]，按16位分组累加）
        int[] dataArr = tcpPack.getTcpS().getData();
        if (dataArr != null && dataArr.length > 0) {
            for (int data : dataArr) {
                // 每个int是32位，拆分为两个16位（高16位 + 低16位）累加（默认32位有效数据）
                checkSum += (data >> 16) & 0xFFFF;
                checkSum = handleCarry(checkSum);
                checkSum += data & 0xFFFF;
                checkSum = handleCarry(checkSum);
            }
            // 可选：若int[]中每个元素仅低16位有效，替换为下方逻辑
            /*
            for (int data : dataArr) {
                checkSum += data & 0xFFFF;
                checkSum = handleCarry(checkSum);
            }
            */
        }

        // 5. 对16位累加和取反，得到最终校验和
        return (short) ~(checkSum & 0xFFFF);
    }

    /**
     * 辅助函数：处理累加后的进位（将32位int的高位进位加到低位，保证结果为16位有效）
     * @param sum 累加和（可能超过16位）
     * @return 处理进位后的16位有效累加和
     */
    private static int handleCarry(int sum) {
        // 循环处理进位，直到高16位为0
        while ((sum & 0xFFFF0000) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return sum;
    }


}