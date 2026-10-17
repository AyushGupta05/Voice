package sc.fidelis.penny.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.UIManager;

import sc.fidelis.ui.Theme;

/**
 * One line of conversation in the Penny strip: styled runs ("“what you said”", " → ", "Penny's answer") painted in the
 * strip's font, shortened with an ellipsis when the window is narrow. What was said is shortened first, so the answer
 * stays readable; the full text is in the tooltip. EDT only.
 */
final class ConversationLine extends JComponent {
    enum Style { PARTIAL, SAID, SEPARATOR, ACK, ANSWER, ERROR, HINT, CODE }

    record Run(String text, Style style) {}

    private final List<Run> runs = new ArrayList<>();
    private static final int MIN_SAID_PX = 90;

    ConversationLine() {
        setOpaque(false);
        setPreferredSize(new Dimension(100, 18));
        setMinimumSize(new Dimension(20, 18));
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
    }

    void set(List<Run> content) {
        if (content.equals(runs)) return;
        runs.clear();
        runs.addAll(content);
        String plain = text();
        setToolTipText(plain.isBlank() ? null : plain);
        repaint();
    }

    /** The whole line as plain text (test hook and tooltip). */
    String text() {
        StringBuilder sb = new StringBuilder();
        for (Run r : runs) sb.append(r.text());
        return sb.toString();
    }

    List<Run> runs() {
        return List.copyOf(runs);
    }

    private Font font(Style s) {
        Font base = getFont() != null ? getFont() : UIManager.getFont("Label.font");
        return switch (s) {
            case PARTIAL, SAID -> base.deriveFont(Font.ITALIC);
            case CODE -> new Font(Font.MONOSPACED, Font.PLAIN, base.getSize());
            default -> base;
        };
    }

    private Color color(Style s) {
        Color fg = UIManager.getColor("Label.foreground");
        if (fg == null) fg = Color.BLACK;
        return switch (s) {
            case PARTIAL, ANSWER -> fg;
            case SAID, SEPARATOR, ACK, HINT, CODE -> Theme.muted();
            case ERROR -> Theme.error();
        };
    }

    @Override
    protected void paintComponent(Graphics g0) {
        if (runs.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        int available = getWidth();
        List<String> texts = fit(available, g);
        int x = 0;
        FontMetrics base = g.getFontMetrics(font(Style.ANSWER));
        int baseline = (getHeight() + base.getAscent() - base.getDescent()) / 2;
        for (int i = 0; i < runs.size(); i++) {
            String t = texts.get(i);
            if (t.isEmpty()) continue;
            Run r = runs.get(i);
            g.setFont(font(r.style()));
            g.setColor(color(r.style()));
            javax.swing.plaf.basic.BasicGraphicsUtils.drawString(this, g, t, x, baseline); // the label's own text rendering
            x += g.getFontMetrics().stringWidth(t);
            if (x >= available) break;
        }
        g.dispose();
    }

    /** The text of each run after shortening to {@code width}: SAID runs first (down to a minimum), then the tail. */
    List<String> fit(int width, Graphics g) {
        List<String> out = new ArrayList<>();
        int total = 0;
        int[] w = new int[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            out.add(runs.get(i).text());
            w[i] = g.getFontMetrics(font(runs.get(i).style())).stringWidth(runs.get(i).text());
            total += w[i];
        }
        if (total <= width) return out;
        for (int i = 0; i < runs.size() && total > width; i++) {
            Run r = runs.get(i);
            if (r.style() != Style.SAID && r.style() != Style.PARTIAL) continue; // what was said gives way to the answer
            FontMetrics fm = g.getFontMetrics(font(r.style()));
            int target = Math.max(MIN_SAID_PX, w[i] - (total - width));
            if (target >= w[i]) continue;
            String s = r.style() == Style.PARTIAL ? ellipsizeStart(r.text(), fm, target) : ellipsize(r.text(), fm, target);
            int nw = fm.stringWidth(s);
            total += nw - w[i];
            w[i] = nw;
            out.set(i, s);
        }
        if (total > width) {
            // still too long: cut from the end of the line
            int x = 0;
            for (int i = 0; i < runs.size(); i++) {
                FontMetrics fm = g.getFontMetrics(font(runs.get(i).style()));
                if (x + w[i] <= width) {
                    x += w[i];
                    continue;
                }
                out.set(i, ellipsize(out.get(i), fm, Math.max(0, width - x)));
                for (int k = i + 1; k < runs.size(); k++) out.set(k, "");
                break;
            }
        }
        return out;
    }

    static String ellipsize(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        String dots = "…";
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(s.substring(0, mid).stripTrailing() + dots) <= width) lo = mid;
            else hi = mid - 1;
        }
        return lo == 0 ? (fm.stringWidth(dots) <= width ? dots : "") : s.substring(0, lo).stripTrailing() + dots;
    }

    /** A live partial transcript keeps its newest words: shorten from the start. */
    static String ellipsizeStart(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        String dots = "…";
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (fm.stringWidth(dots + s.substring(mid).stripLeading()) <= width) hi = mid;
            else lo = mid + 1;
        }
        return lo >= s.length() ? "" : dots + s.substring(lo).stripLeading();
    }
}
