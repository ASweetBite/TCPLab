package com.ouc.tcp.visual;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RenoLogVisualizer extends JFrame {

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
            Matcher m = Pattern.compile("(seq:|Base:|Num:)\\s*(\\d+)").matcher(info);
            if (m.find()) return Integer.parseInt(m.group(2));
            return -1;
        }

        public int getDupCount() {
            Matcher m = Pattern.compile("count:\\s*(\\d+)").matcher(info);
            if (m.find()) return Integer.parseInt(m.group(1));
            return 0;
        }

        // 判断是否为“值得关注”的差错/异常事件
        public boolean isErrorEvent() {
            return type.equals("TIMEOUT") || type.equals("FAST_RETRANS") ||
                    type.equals("DUP_ACK") || type.equals("RETRANSMIT");
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
                    e.time = line.substring(0, 23).trim();
                    e.type = m.group(1);
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

            // 正常包在高速模式下进一步提速
            boolean isNormal = current.type.equals("SEND") || current.type.equals("NEW_ACK");
            boolean isInstant = current.type.equals("WINDOW_SLIDE") || current.type.equals("EXIT_FR")
                    || current.type.equals("FR_INFLATE") || current.type.equals("INIT");

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

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int senderX = 100, receiverX = w - 100, lineY = h / 2;

            g2.setColor(new Color(44, 62, 80));
            g2.fillRoundRect(senderX - 40, lineY - 60, 80, 120, 15, 15);
            g2.fillRoundRect(receiverX - 40, lineY - 60, 80, 120, 15, 15);

            g2.setColor(Color.LIGHT_GRAY);
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(senderX + 40, lineY, receiverX - 40, lineY);

            if (events.isEmpty()) return;
            RenoEvent e = getCurrentEvent();

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Consolas", Font.BOLD, 14));
            g2.drawString(String.format("Step: %d/%d | %s", currentIndex + 1, events.size(), e.time), 20, 25);

            if (e.isErrorEvent()) {
                g2.setColor(new Color(231, 76, 60));
                g2.fillRect(0, 40, w, 40);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Consolas", Font.BOLD, 20));
                g2.drawString("⚠️ ATTENTION: " + e.type + " - " + e.info, 50, 68);
            }

            if (progress < 1.0 && !finished) {
                int targetSeq = e.getTargetSeq();
                if (targetSeq != -1) {
                    if (e.type.equals("SEND") || e.type.equals("FAST_RETRANS") || e.type.equals("RETRANSMIT")) {
                        int x = (int) (senderX + 40 + (receiverX - senderX - 80) * progress);
                        drawPacket(g2, x, lineY - 20, "D:" + targetSeq, e.type.equals("SEND") ? new Color(52, 152, 219) : new Color(155, 89, 182));
                    } else if (e.type.equals("NEW_ACK") || e.type.equals("DUP_ACK")) {
                        int x = (int) (receiverX - 40 - (receiverX - senderX - 80) * progress);
                        drawPacket(g2, x, lineY + 20, (e.type.equals("DUP_ACK")?"D-ACK:":"ACK:") + targetSeq, e.type.equals("NEW_ACK") ? new Color(46, 204, 113) : new Color(243, 156, 18));
                    }
                }
            }
        }

        private void drawPacket(Graphics2D g2, int x, int y, String text, Color color) {
            g2.setColor(color);
            g2.fillRoundRect(x - 30, y - 15, 60, 30, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawString(text, x - 25, y + 5);
        }
    }

    // =========================
    // 发送窗口面板
    // =========================
    static class WindowStatePanel extends JPanel {
        private RenoEvent currentEvent;
        private final int MSS = 100;

        public WindowStatePanel() {
            setPreferredSize(new Dimension(1000, 200));
            setBackground(new Color(240, 240, 240));
        }

        public void updateState(RenoEvent e) { this.currentEvent = e; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (currentEvent == null) return;
            Graphics2D g2 = (Graphics2D) g;

            // 绘制统计数据
            g2.setColor(new Color(44, 62, 80));
            g2.fillRect(10, 10, 960, 40);
            g2.setColor(Color.WHITE);
            g2.drawString("State: " + currentEvent.tcpState + " | Cwnd: " + currentEvent.cwnd + " | Ssthresh: " + currentEvent.ssthresh, 30, 35);

            // 绘制窗口
            int startX = 30, y = 80, boxW = 50, boxH = 40, gap = 5;
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
                g2.drawString(String.valueOf(idx * MSS + 1), x + 5, y + 25);
            }

            // Cwnd 黄框
            g2.setColor(new Color(241, 196, 15, 100));
            int winX = startX + (baseIdx - renderStart) * (boxW + gap);
            int winW = (currentEvent.cwnd / MSS) * (boxW + gap);
            g2.setStroke(new BasicStroke(3f));
            g2.drawRect(winX, y - 5, winW, boxH + 10);
        }
    }

    // =========================
    // 主框架逻辑
    // =========================
    private int lastPausingIndex = -1; // 防止在同一事件循环暂停

    public RenoLogVisualizer(List<RenoEvent> events) {
        setTitle("TCP Reno Visualizer (Auto-Error-Detection)");
        setSize(1000, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        AnimationPanel animationPanel = new AnimationPanel(events);
        WindowStatePanel windowPanel = new WindowStatePanel();
        add(animationPanel, BorderLayout.CENTER);
        add(windowPanel, BorderLayout.SOUTH);

        // 控制栏
        JCheckBox autoPause = new JCheckBox("Auto-pause on Error", true);
        JButton playBtn = new JButton("Play / Pause");
        JButton stepBtn = new JButton("Next Step");
        JSlider speedSlider = new JSlider(1, 100, 20); // 最小1ms，极大提速
        speedSlider.setInverted(true);

        JPanel controlPanel = new JPanel();
        controlPanel.add(autoPause);
        controlPanel.add(new JLabel("  Speed:"));
        controlPanel.add(speedSlider);
        controlPanel.add(playBtn);
        controlPanel.add(stepBtn);
        add(controlPanel, BorderLayout.NORTH);

        Timer timer = new Timer(speedSlider.getValue(), e -> {
            animationPanel.nextFrame();
            RenoEvent current = animationPanel.getCurrentEvent();
            if (current != null) {
                windowPanel.updateState(current);

                // --- 差错自动暂停逻辑 ---
                int idx = animationPanel.getCurrentIndex();
                if (autoPause.isSelected() && current.isErrorEvent() && idx != lastPausingIndex) {
                    ((Timer)e.getSource()).stop();
                    lastPausingIndex = idx;
                    JOptionPane.showMessageDialog(this,
                            "Error Detected!\nEvent: " + current.type + "\nInfo: " + current.info +
                                    "\n\nWindow state at this moment has been captured.",
                            "Protocol Error Insight", JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        speedSlider.addChangeListener(e -> timer.setDelay(speedSlider.getValue()));
        playBtn.addActionListener(e -> { if(timer.isRunning()) timer.stop(); else timer.start(); });
        stepBtn.addActionListener(e -> { timer.stop(); animationPanel.nextFrame(); windowPanel.updateState(animationPanel.getCurrentEvent()); });
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser(new File("."));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                try {
                    List<RenoEvent> events = RenoLogParser.parse(Files.readAllLines(chooser.getSelectedFile().toPath()));
                    new RenoLogVisualizer(events).setVisible(true);
                } catch (Exception e) { e.printStackTrace(); }
            }
        });
    }
}