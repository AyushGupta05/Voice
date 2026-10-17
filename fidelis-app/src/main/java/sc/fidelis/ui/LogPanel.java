package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;

import sc.fidelis.engine.api.EngineEvent;

/** The ImageJ/engine log (docs D12 UI-20): read-only, timestamped, copyable. */
public final class LogPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final JTextArea text = new JTextArea();

    LogPanel(UiContext ctx) {
        super(new BorderLayout());
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane sp = new JScrollPane(text);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()));
        add(sp, BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()), null));
        JButton clear = new JButton("Clear view");
        clear.addActionListener(e -> text.setText(""));
        bar.add(copy);
        bar.add(clear);
        add(bar, BorderLayout.NORTH);
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.LogLine l) append(l.line());
    }

    void append(String line) {
        text.append("[" + LocalTime.now().format(TIME) + "] " + line + "\n");
    }

    String content() {
        return text.getText();
    }
}
