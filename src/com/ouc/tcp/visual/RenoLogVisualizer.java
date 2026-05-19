package com.ouc.tcp.visual;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TCP Reno / NewReno 日志可视化工具。
 *
 * 功能：
 * 1. 动画展示 DATA / ACK / DUP_ACK / 重传过程。
 * 2. 动态展示发送窗口随发送包与 ACK 的变化。
 * 3. 绘制 cwnd 和 ssthresh 折线图。
 * 4. 绘制包传输时序图，展示 DATA、ACK、重传、快速重传、超时等事件。
 */
public class RenoLogVisualizer extends JFrame {

    private static final int MSS = 100;

    // =========================
    // 数据模型
    // =========================
    static class RenoEvent {
        String time;
        String type;
        int base;
        int nextSeq;
        int cwnd;
        int ssthresh;
        String tcpState;
        String info;

        public int getTargetSeq() {
            Pattern p = Pattern.compile(
                    "(DATA_seq:|Retransmit seq:|Resend Base:|AckNum:|Base:|seq:|Num:)\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher m = p.matcher(info);
            if (m.find()) return Integer.parseInt(m.group(2));
            return -1;
        }

        public int getDupCount() {
            Matcher m = Pattern.compile("count:\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(info);
            if (m.find()) return Integer.parseInt(m.group(1));
            return 0;
        }

        public boolean isSendLike() {
            return type.equals("SEND")
                    || type.equals("FAST_RETRANS")
                    || type.equals("RETRANSMIT")
                    || type.equals("NEWRENO_RETRANS");
        }

        public boolean isAckLike() {
            return type.equals("NEW_ACK")
                    || type.equals("DUP_ACK")
                    || type.equals("PARTIAL_ACK")
                    || type.equals("FULL_ACK");
        }

        // 判断是否为“值得关注”的差错/异常事件
        public boolean isErrorEvent() {
            return type.equals("TIMEOUT")
                    || type.equals("FAST_RETRANS")
                    || type.equals("NEWRENO_RETRANS")
                    || type.equals("DUP_ACK")
                    || type.equals("RETRANSMIT")
                    || type.equals("PARTIAL_ACK");
        }
    }

    static class RenoLogParser {
        private static final Pattern LOG_PATTERN = Pattern.compile(
                "EVENT:\\s*(\\S+)\\s*base:\\s*(\\d+)\\s*next:\\s*(\\d+)\\s*cwnd:\\s*(\\d+)\\s*ssthresh:\\s*(\\d+)\\s*state:\\s*(\\S+)\\s*info:\\s*(.*)"
        );

        public static List<RenoEvent> parse(List<String> lines) {
            List<RenoEvent> events = new ArrayList<>();
            for (String line : lines) {
                Matcher m = LOG_PATTERN.matcher(line);
                if (m.find()) {
                    RenoEvent e = new RenoEvent();
                    e.time = line.substring(0, Math.min(23, line.length())).trim();
                    e.type = m.group(1).trim();
                    e.base = Integer.parseInt(m.group(2));
                    e.nextSeq = Integer.parseInt(m.group(3));
                    e.cwnd = Integer.parseInt(m.group(4));
                    e.ssthresh = Integer.parseInt(m.group(5));
                    e.tcpState = m.group(6);
                    e.info = m.group(7);
                    events.add(e);
                }
            }
            return events;
        }
    }

    // =========================
    // 动画面板
    // =========================
    static class AnimationPanel extends JPanel {
        private final List<RenoEvent> events;
        private int currentIndex = 0;
        private double progress = 0.0;
        private boolean finished = false;

        public AnimationPanel(List<RenoEvent> events) {
            this.events = events;
            setBackground(Color.WHITE);
        }

        public void nextFrame() {
            if (events.isEmpty() || finished) return;
            RenoEvent current = events.get(currentIndex);

            boolean isNormal = current.type.equals("SEND") || current.type.equals("NEW_ACK");
            boolean isInstant = current.type.equals("WINDOW_SLIDE")
                    || current.type.equals("EXIT_FR")
                    || current.type.equals("ENTER_FR")
                    || current.type.equals("FR_INFLATE")
                    || current.type.equals("CWND_UPDATE")
                    || current.type.equals("INIT");

            double step = isInstant ? 0.5 : (isNormal ? 0.15 : 0.05);
            progress += step;

            if (progress >= 1.0) {
                progress = 0.0;
                currentIndex++;
                if (currentIndex >= events.size()) {
                    currentIndex = events.size() - 1;
                    finished = true;
                }
            }
            repaint();
        }

        public int getCurrentIndex() { return currentIndex; }

        public RenoEvent getCurrentEvent() {
            if (events.isEmpty()) return null;
            return events.get(Math.min(currentIndex, events.size() - 1));
        }

        public void setCurrentIndex(int idx) {
            if (events.isEmpty()) return;
            currentIndex = Math.max(0, Math.min(idx, events.size() - 1));
            progress = 0.0;
            finished = false;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int senderX = 100, receiverX = w - 100, lineY = h / 2;

            g2.setColor(new Color(44, 62, 80));
            g2.fillRoundRect(senderX - 45, lineY - 60, 90, 120, 15, 15);
            g2.fillRoundRect(receiverX - 45, lineY - 60, 90, 120, 15, 15);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Consolas", Font.BOLD, 14));
            g2.drawString("Sender", senderX - 28, lineY + 5);
            g2.drawString("Receiver", receiverX - 34, lineY + 5);

            g2.setColor(Color.LIGHT_GRAY);
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(senderX + 45, lineY, receiverX - 45, lineY);

            if (events.isEmpty()) return;
            RenoEvent e = getCurrentEvent();

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Consolas", Font.BOLD, 14));
            g2.drawString(String.format("Step: %d/%d | %s | %s", currentIndex + 1, events.size(), e.time, e.type), 20, 25);
            g2.drawString("Info: " + e.info, 20, 45);

            if (e.isErrorEvent()) {
                g2.setColor(new Color(231, 76, 60));
                g2.fillRect(0, 60, w, 36);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Consolas", Font.BOLD, 18));
                g2.drawString("ATTENTION: " + e.type + " - " + e.info, 50, 84);
            }

            if (progress < 1.0 && !finished) {
                int targetSeq = e.getTargetSeq();
                if (targetSeq != -1) {
                    if (e.isSendLike()) {
                        int x = (int) (senderX + 45 + (receiverX - senderX - 90) * progress);
                        Color c = e.type.equals("SEND") ? new Color(52, 152, 219) : new Color(155, 89, 182);
                        drawPacket(g2, x, lineY - 20, "D:" + targetSeq, c);
                    } else if (e.isAckLike()) {
                        int x = (int) (receiverX - 45 - (receiverX - senderX - 90) * progress);
                        Color c = e.type.equals("NEW_ACK") || e.type.equals("FULL_ACK")
                                ? new Color(46, 204, 113)
                                : new Color(243, 156, 18);
                        drawPacket(g2, x, lineY + 25, (e.type.equals("DUP_ACK") ? "D-ACK:" : "ACK:") + targetSeq, c);
                    }
                }
            }
        }

        private void drawPacket(Graphics2D g2, int x, int y, String text, Color color) {
            g2.setColor(color);
            g2.fillRoundRect(x - 38, y - 15, 76, 30, 8, 8);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Consolas", Font.BOLD, 12));
            g2.drawString(text, x - 32, y + 5);
        }
    }

    // =========================
    // 发送窗口面板
    // =========================
    static class WindowStatePanel extends JPanel {
        private RenoEvent currentEvent;

        public WindowStatePanel() {
            setPreferredSize(new Dimension(1000, 190));
            setBackground(new Color(240, 240, 240));
        }

        public void updateState(RenoEvent e) {
            this.currentEvent = e;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (currentEvent == null) return;
            Graphics2D g2 = (Graphics2D) g;

            g2.setColor(new Color(44, 62, 80));
            g2.fillRect(10, 10, Math.max(10, getWidth() - 20), 40);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Consolas", Font.BOLD, 13));
            g2.drawString("State: " + currentEvent.tcpState
                    + " | cwnd: " + currentEvent.cwnd
                    + " | ssthresh: " + currentEvent.ssthresh
                    + " | base: " + currentEvent.base
                    + " | next: " + currentEvent.nextSeq, 30, 35);

            int startX = 30, y = 85, boxW = 50, boxH = 40, gap = 5;
            int baseIdx = currentEvent.base / MSS;
            int nextIdx = currentEvent.nextSeq / MSS;
            int renderStart = Math.max(0, baseIdx - 2);

            for (int i = 0; i < 18; i++) {
                int idx = renderStart + i;
                int x = startX + i * (boxW + gap);
                if (idx < baseIdx) g2.setColor(new Color(46, 204, 113));
                else if (idx < nextIdx) g2.setColor(new Color(52, 152, 219));
                else g2.setColor(Color.LIGHT_GRAY);

                g2.fillRoundRect(x, y, boxW, boxH, 5, 5);
                g2.setColor(Color.BLACK);
                g2.setFont(new Font("Consolas", Font.PLAIN, 11));
                g2.drawString(String.valueOf(idx * MSS + 1), x + 5, y + 25);
            }

            g2.setColor(new Color(241, 196, 15, 120));
            int winX = startX + (baseIdx - renderStart) * (boxW + gap);
            int winW = Math.max(boxW, (currentEvent.cwnd / MSS) * (boxW + gap));
            g2.setStroke(new BasicStroke(3f));
            g2.drawRect(winX, y - 5, winW, boxH + 10);

            g2.setColor(new Color(120, 120, 120));
            g2.setFont(new Font("Consolas", Font.PLAIN, 12));
            g2.drawString("green=acked, blue=sent/unacked, gray=not sent, yellow=cwnd", 30, 160);
        }
    }

    // =========================
    // cwnd / ssthresh 折线图
    // =========================
    static class CwndChartPanel extends JPanel {
        private final List<RenoEvent> events;
        private int currentIndex = 0;

        public CwndChartPanel(List<RenoEvent> events) {
            this.events = events;
            setBackground(Color.WHITE);
        }

        public void setCurrentIndex(int idx) {
            this.currentIndex = Math.max(0, Math.min(idx, Math.max(0, events.size() - 1)));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (events.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int left = 60, right = 30, top = 40, bottom = 50;
            int plotW = w - left - right;
            int plotH = h - top - bottom;

            int maxY = MSS;
            for (int i = 0; i <= currentIndex && i < events.size(); i++) {
                maxY = Math.max(maxY, Math.max(events.get(i).cwnd, events.get(i).ssthresh));
            }
            maxY = ((maxY + MSS - 1) / MSS + 1) * MSS;

            g2.setColor(Color.BLACK);
            g2.drawLine(left, top, left, top + plotH);
            g2.drawLine(left, top + plotH, left + plotW, top + plotH);
            g2.setFont(new Font("Consolas", Font.PLAIN, 12));
            g2.drawString("cwnd / ssthresh", left, 22);
            g2.drawString("event index", left + plotW - 90, top + plotH + 35);

            for (int y = 0; y <= maxY; y += Math.max(MSS, maxY / 8)) {
                int py = top + plotH - (int) (plotH * (y / (double) maxY));
                g2.setColor(new Color(230, 230, 230));
                g2.drawLine(left, py, left + plotW, py);
                g2.setColor(Color.GRAY);
                g2.drawString(String.valueOf(y / MSS), 20, py + 4);
            }

            drawLine(g2, left, top, plotW, plotH, maxY, true, new Color(52, 152, 219));
            drawLine(g2, left, top, plotW, plotH, maxY, false, new Color(231, 76, 60));

            g2.setColor(new Color(52, 152, 219));
            g2.fillRect(left + 10, top + 10, 12, 12);
            g2.setColor(Color.BLACK);
            g2.drawString("cwnd", left + 28, top + 21);

            g2.setColor(new Color(231, 76, 60));
            g2.fillRect(left + 90, top + 10, 12, 12);
            g2.setColor(Color.BLACK);
            g2.drawString("ssthresh", left + 108, top + 21);
        }

        private void drawLine(Graphics2D g2, int left, int top, int plotW, int plotH, int maxY, boolean drawCwnd, Color color) {
            int n = Math.max(1, currentIndex + 1);
            int prevX = -1, prevY = -1;
            g2.setColor(color);
            g2.setStroke(new BasicStroke(2f));

            for (int i = 0; i <= currentIndex && i < events.size(); i++) {
                RenoEvent e = events.get(i);
                int value = drawCwnd ? e.cwnd : e.ssthresh;
                int x = left + (n == 1 ? 0 : (int) (plotW * (i / (double) (n - 1))));
                int y = top + plotH - (int) (plotH * (value / (double) maxY));

                if (prevX >= 0) {
                    g2.drawLine(prevX, prevY, x, y);
                }
                g2.fillOval(x - 2, y - 2, 4, 4);

                prevX = x;
                prevY = y;
            }
        }
    }

    // =========================
    // 包传输时序图
    // =========================
    static class PacketTimelinePanel extends JPanel {
        private final List<RenoEvent> events;
        private int currentIndex = 0;

        public PacketTimelinePanel(List<RenoEvent> events) {
            this.events = events;
            setBackground(Color.WHITE);
        }

        public void setCurrentIndex(int idx) {
            this.currentIndex = Math.max(0, Math.min(idx, Math.max(0, events.size() - 1)));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (events.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int left = 80, right = 80, top = 60, bottom = 40;
            int senderY = top;
            int receiverY = h - bottom;

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Consolas", Font.BOLD, 14));
            g2.drawString("Sender", 15, senderY + 5);
            g2.drawString("Receiver", 15, receiverY + 5);

            g2.setColor(new Color(180, 180, 180));
            g2.drawLine(left, senderY, w - right, senderY);
            g2.drawLine(left, receiverY, w - right, receiverY);

            int start = Math.max(0, currentIndex - 70);
            int end = currentIndex;
            int count = Math.max(1, end - start + 1);

            for (int i = start; i <= end && i < events.size(); i++) {
                RenoEvent e = events.get(i);
                int x = left + (int) ((w - left - right) * ((i - start) / (double) Math.max(1, count - 1)));
                int target = e.getTargetSeq();

                if (e.isSendLike()) {
                    Color c = e.type.equals("SEND") ? new Color(52, 152, 219) : new Color(155, 89, 182);
                    drawArrow(g2, x, senderY + 8, x, receiverY - 8, c);
                    drawLabel(g2, x, (senderY + receiverY) / 2, e.type + "\n" + target, c);
                } else if (e.isAckLike()) {
                    Color c = e.type.equals("DUP_ACK") || e.type.equals("PARTIAL_ACK")
                            ? new Color(243, 156, 18)
                            : new Color(46, 204, 113);
                    drawArrow(g2, x, receiverY - 8, x, senderY + 8, c);
                    drawLabel(g2, x, (senderY + receiverY) / 2, e.type + "\n" + target, c);
                } else if (e.type.equals("TIMEOUT")) {
                    g2.setColor(new Color(231, 76, 60));
                    g2.setStroke(new BasicStroke(3f));
                    g2.drawLine(x, senderY - 15, x, receiverY + 15);
                    drawLabel(g2, x, (senderY + receiverY) / 2, "TIMEOUT", new Color(231, 76, 60));
                }
            }

            g2.setColor(Color.GRAY);
            g2.setFont(new Font("Consolas", Font.PLAIN, 12));
            g2.drawString("Showing recent events: " + start + " - " + end, left, h - 15);
        }

        private void drawArrow(Graphics2D g2, int x1, int y1, int x2, int y2, Color c) {
            g2.setColor(c);
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x1, y1, x2, y2);

            int dir = y2 > y1 ? 1 : -1;
            int arrowY = y2;
            int[] xs = {x2, x2 - 5, x2 + 5};
            int[] ys = {arrowY, arrowY - dir * 10, arrowY - dir * 10};
            g2.fillPolygon(xs, ys, 3);
        }

        private void drawLabel(Graphics2D g2, int x, int y, String text, Color c) {
            String[] parts = text.split("\\n");
            g2.setColor(c);
            g2.setFont(new Font("Consolas", Font.BOLD, 10));
            for (int i = 0; i < parts.length; i++) {
                g2.drawString(parts[i], x + 4, y + i * 12);
            }
        }
    }

    // =========================
    // 主框架逻辑
    // =========================
    private int lastPausingIndex = -1;

    public RenoLogVisualizer(List<RenoEvent> events) {
        setTitle("TCP Reno/NewReno Visualizer");
        setSize(1100, 760);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        AnimationPanel animationPanel = new AnimationPanel(events);
        WindowStatePanel windowPanel = new WindowStatePanel();
        CwndChartPanel chartPanel = new CwndChartPanel(events);
        PacketTimelinePanel timelinePanel = new PacketTimelinePanel(events);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Packet Animation", animationPanel);
        tabs.addTab("cwnd / ssthresh Chart", chartPanel);
        tabs.addTab("Packet Timeline", timelinePanel);

        add(tabs, BorderLayout.CENTER);
        add(windowPanel, BorderLayout.SOUTH);

        JCheckBox autoPause = new JCheckBox("Auto-pause on Error", true);
        JButton playBtn = new JButton("Play / Pause");
        JButton stepBtn = new JButton("Next Step");
        JButton prevBtn = new JButton("Prev Step");
        JSlider speedSlider = new JSlider(1, 100, 20);
        speedSlider.setInverted(true);

        JPanel controlPanel = new JPanel();
        controlPanel.add(autoPause);
        controlPanel.add(new JLabel("  Speed:"));
        controlPanel.add(speedSlider);
        controlPanel.add(prevBtn);
        controlPanel.add(stepBtn);
        controlPanel.add(playBtn);
        add(controlPanel, BorderLayout.NORTH);

        Timer timer = new Timer(speedSlider.getValue(), e -> {
            animationPanel.nextFrame();
            updateAllPanels(animationPanel, windowPanel, chartPanel, timelinePanel);

            RenoEvent current = animationPanel.getCurrentEvent();
            if (current != null) {
                int idx = animationPanel.getCurrentIndex();
                if (autoPause.isSelected() && current.isErrorEvent() && idx != lastPausingIndex) {
                    ((Timer) e.getSource()).stop();
                    lastPausingIndex = idx;
                    JOptionPane.showMessageDialog(this,
                            "Event Detected!\nEvent: " + current.type + "\nInfo: " + current.info,
                            "Protocol Insight", JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        speedSlider.addChangeListener(e -> timer.setDelay(speedSlider.getValue()));

        playBtn.addActionListener(e -> {
            if (timer.isRunning()) timer.stop();
            else timer.start();
        });

        stepBtn.addActionListener(e -> {
            timer.stop();
            animationPanel.nextFrame();
            updateAllPanels(animationPanel, windowPanel, chartPanel, timelinePanel);
        });

        prevBtn.addActionListener(e -> {
            timer.stop();
            animationPanel.setCurrentIndex(animationPanel.getCurrentIndex() - 1);
            updateAllPanels(animationPanel, windowPanel, chartPanel, timelinePanel);
        });

        if (!events.isEmpty()) {
            windowPanel.updateState(events.get(0));
            chartPanel.setCurrentIndex(0);
            timelinePanel.setCurrentIndex(0);
        }
    }

    private void updateAllPanels(AnimationPanel animationPanel,
                                 WindowStatePanel windowPanel,
                                 CwndChartPanel chartPanel,
                                 PacketTimelinePanel timelinePanel) {
        RenoEvent current = animationPanel.getCurrentEvent();
        int idx = animationPanel.getCurrentIndex();
        if (current != null) {
            windowPanel.updateState(current);
            chartPanel.setCurrentIndex(idx);
            timelinePanel.setCurrentIndex(idx);
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser(new File("."));
            chooser.setFileFilter(new FileNameExtensionFilter("TCP log files", "log", "txt"));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                try {
                    List<RenoEvent> events = RenoLogParser.parse(Files.readAllLines(chooser.getSelectedFile().toPath()));
                    if (events.isEmpty()) {
                        JOptionPane.showMessageDialog(null,
                                "No valid events found in log file.",
                                "Parse Failed",
                                JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    new RenoLogVisualizer(events).setVisible(true);
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(null,
                            "Failed to parse log: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }
}
