package com.ouc.tcp.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/**
 * TCPLab 自定义配置读取类。
 *
 * 支持从项目根目录读取：
 * 1. Config.ini
 * 2. tcplab.properties
 *
 * 如果两个文件都存在，tcplab.properties 中的同名配置会覆盖 Config.ini。
 *
 * 可用配置示例：
 *
 * protocol=TCP_NEW_RENO
 * tcp.log=tcp_newreno.log
 * tcp.timeout=1000
 * tcp.data.eflag=2
 * tcp.ack.eflag=0
 * tcp.retrans.eflag=0
 * tcp.freezeInFastRecovery=true
 */
public class TcpLabConfig {

    public enum Protocol {
        TCP_RENO,
        TCP_NEW_RENO;

        static Protocol from(String value) {
            if (value == null) return TCP_NEW_RENO;
            String normalized = value.trim()
                    .toUpperCase(Locale.ROOT)
                    .replace("-", "_")
                    .replace(" ", "_");

            if ("RENO".equals(normalized) || "TCP_RENO".equals(normalized)) {
                return TCP_RENO;
            }
            if ("NEW_RENO".equals(normalized)
                    || "NEWRENO".equals(normalized)
                    || "TCP_NEW_RENO".equals(normalized)
                    || "TCP_NEWRENO".equals(normalized)) {
                return TCP_NEW_RENO;
            }
            return TCP_NEW_RENO;
        }
    }

    private static final TcpLabConfig INSTANCE = new TcpLabConfig();

    private final Properties props = new Properties();

    private TcpLabConfig() {
        // 先读 Config.ini，再读 tcplab.properties，使后者可以覆盖前者。
        loadIfExists("Config.ini");
        loadIfExists("tcplab.properties");
    }

    public static TcpLabConfig get() {
        return INSTANCE;
    }

    private void loadIfExists(String fileName) {
        File file = new File(fileName);
        if (!file.exists() || !file.isFile()) {
            return;
        }

        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
            System.out.println("[TcpLabConfig] Loaded config file: " + file.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("[TcpLabConfig] Failed to load config file: " + file.getAbsolutePath());
            e.printStackTrace();
        }
    }

    private String getString(String key, String defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private int getInt(String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.out.println("[TcpLabConfig] Invalid int for key " + key + ": " + value
                    + ", use default: " + defaultValue);
            return defaultValue;
        }
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value);
    }

    public Protocol getProtocol() {
        String value = props.getProperty("protocol");
        if (value == null) value = props.getProperty("tcp.protocol");
        return Protocol.from(value);
    }

    public boolean isNewReno() {
        return getProtocol() == Protocol.TCP_NEW_RENO;
    }

    public String getLogFile() {
        return getString("tcp.log", isNewReno() ? "tcp_newreno.log" : "tcp_reno.log");
    }

    public int getTimeout() {
        return getInt("tcp.timeout", 1000);
    }

    /**
     * 发送端 DATA 包差错控制标志。
     * 建议测试 NewReno 时先使用 2，只模拟 DATA 丢包。
     */
    public int getDataEflag() {
        return getInt("tcp.data.eflag", getInt("data.eflag", 2));
    }

    /**
     * 接收端 ACK 包差错控制标志。
     * 建议测试 NewReno 时先使用 0，保证 ACK 可靠，便于观察 DupACK / Partial ACK。
     */
    public int getAckEflag() {
        return getInt("tcp.ack.eflag", getInt("ack.eflag", 0));
    }

    /**
     * 重传包差错控制标志。
     * 建议实验展示时使用 0，避免重传包再次被丢导致日志难以观察。
     */
    public int getRetransEflag() {
        return getInt("tcp.retrans.eflag", getInt("retrans.eflag", 0));
    }

    /**
     * 是否在快速恢复阶段暂停接收新的应用层数据。
     *
     * true：更适合课程实验展示，避免快速恢复期间继续灌入大量新包。
     * false：更接近真实 TCP，但需要更完整的 pipe / flightSize 控制。
     */
    public boolean freezeInFastRecovery() {
        return getBoolean("tcp.freezeInFastRecovery", true);
    }
}
