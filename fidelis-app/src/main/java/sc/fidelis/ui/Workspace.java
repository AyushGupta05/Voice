package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

import net.imagej.ImageJ;
import sc.fidelis.Bootstrap;

/**
 * Placeholder main window created by the M0 bootstrap. The Desktop UI workstream replaces this
 * with the real Fidelis workspace (docs D12; architecture-gate hybrid B).
 */
public final class Workspace {
    private Workspace() {}

    public static void launch(ImageJ ij, String[] args) {
        JFrame frame = new JFrame("Fidelis");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                frame.dispose();
                new Thread(() -> Bootstrap.exit(ij, 0), "fidelis-quit").start();
            }
        });
        JLabel label = new JLabel("Fidelis bootstrap: engine context ready (workspace not yet built)", SwingConstants.CENTER);
        frame.getContentPane().add(label, BorderLayout.CENTER);
        frame.setSize(960, 640);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }
}
