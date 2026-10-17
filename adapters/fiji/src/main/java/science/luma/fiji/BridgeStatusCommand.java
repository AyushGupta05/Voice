package science.luma.fiji;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.plugin.Parameter;

/** Plugins > Luma > Bridge status: starts the bridge if needed and shows where it listens. */
@Plugin(type = Command.class, menuPath = "Plugins>Luma>Bridge status")
public final class BridgeStatusCommand implements Command {
    @Parameter(required = false) private UIService ui;

    @Override public void run() {
        String message = LumaBridge.statusMessage();
        if (ui != null) ui.showDialog(message, "Luma bridge"); else ij.IJ.showMessage("Luma bridge", message);
    }
}
