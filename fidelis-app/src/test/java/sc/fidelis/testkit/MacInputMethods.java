package sc.fidelis.testkit;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.TextComponent;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ContainerEvent;

import javax.swing.text.JTextComponent;

/**
 * Keeps the macOS input-method bridge out of the test JVM's text fields.
 *
 * <p>When a text field that has the focus loses it because another window activates (IJ1 shows its Results,
 * Summary or a new image window, which the engine then hides), AWT clears the field from the window's input
 * method in Java first and tells AppKit afterwards. If macOS's text-insertion indicator (TUINSCursorUIController,
 * macOS 14+) asks the window for its cursor position in between, the JDK's {@code CInputMethod} calls
 * {@code LWCToolkit.invokeAndWait(..., null)}, the NullPointerException becomes an uncaught NSException
 * ("Java Exception") on the AppKit thread, and the whole test JVM aborts (or, with the JDK's
 * {@code JNU_NO_COCOA_EXCEPTION} switch, crashes in {@code jni_GetIntArrayElements}). Observed with Fiji's pinned
 * Zulu 21.0.7 on macOS 26 in WorkspaceMacroEditorTest; independent of the code under test.
 *
 * <p>Text components without input methods are passive clients: AppKit never gets an input method for their window,
 * so the indicator's queries are answered natively and never reach Java. Typing still produces key events (no test
 * uses an input method editor). Applied to every text component that is, or later becomes, part of a window and to
 * every new focus owner (Swing re-enables input methods when a field becomes editable).
 */
final class MacInputMethods {
    private static boolean installed;

    private MacInputMethods() {}

    static synchronized void disableOnMac() {
        if (installed || !System.getProperty("os.name", "").toLowerCase().contains("mac")
                || Boolean.getBoolean("java.awt.headless")) return;
        installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e instanceof ContainerEvent ce && ce.getID() == ContainerEvent.COMPONENT_ADDED) disable(ce.getChild());
        }, AWTEvent.CONTAINER_EVENT_MASK);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", ev -> {
            if (ev.getNewValue() instanceof Component c) disable(c);
        });
        for (Window w : Window.getWindows()) disable(w);
    }

    private static void disable(Component c) {
        if (c instanceof JTextComponent || c instanceof TextComponent) c.enableInputMethods(false);
        if (c instanceof Container k) for (Component child : k.getComponents()) disable(child);
    }
}
