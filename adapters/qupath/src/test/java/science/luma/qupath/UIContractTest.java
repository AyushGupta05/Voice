package science.luma.qupath;

import javafx.application.Platform;
import javafx.scene.Node;
import org.fxmisc.richtext.CodeArea;
import javafx.scene.control.*;
import java.util.*;
import java.util.concurrent.*;

/** JavaFX controls only; creates no windows and touches no scientific application session. */
public final class UIContractTest {
    public static void main(String[] ignored) throws Exception {
        var result=new CompletableFuture<Void>();
        Platform.startup(()->{
            try {
                var bridge=new LumaExtension();
                var classifiers=new ListView<String>();classifiers.getItems().addAll("Classifier A","Classifier B");
                classifiers.getSelectionModel().select(0);String before=fingerprint(bridge,classifiers);
                classifiers.getSelectionModel().select(1);String after=fingerprint(bridge,classifiers);
                if(before.equals(after))throw new AssertionError("Classifier selection must invalidate approval fingerprint");
                var table=new TableView<String>();table.getItems().addAll("Result A","Result B");
                table.getSelectionModel().select(0);before=fingerprint(bridge,table);
                table.getSelectionModel().select(1);after=fingerprint(bridge,table);
                if(before.equals(after))throw new AssertionError("Table selection must invalidate approval fingerprint");
                classifiers.setSelectionModel(null);fingerprint(bridge,classifiers);
                var choices=new ChoiceBox<String>();choices.getItems().addAll("Option A","Option B");
                before=fingerprint(bridge,choices);LumaExtension.selectControlOption(choices,"Option B");
                if(!choices.getValue().equals("Option B")||before.equals(fingerprint(bridge,choices)))throw new AssertionError("Choice selection must apply and invalidate approval");
                var tabs=new TabPane(new Tab("Image"),new Tab("Project"));
                LumaExtension.selectControlOption(tabs,"Project");if(!tabs.getSelectionModel().getSelectedItem().getText().equals("Project"))throw new AssertionError("Tab navigation failed");
                before=fingerprint(bridge,tabs);tabs.getTabs().getFirst().setDisable(true);
                if(before.equals(fingerprint(bridge,tabs)))throw new AssertionError("Disabled tab state must invalidate approval");
                try{LumaExtension.selectControlOption(tabs,"Image");throw new AssertionError("Disabled tab selected");}catch(IllegalArgumentException expected){}
                choices.getItems().add("Option B");
                try{LumaExtension.selectControlOption(choices,"Option B");throw new AssertionError("Ambiguous choice selected");}catch(IllegalArgumentException expected){}
                var editor=new CodeArea();editor.replaceText("println 'Original'");editor.moveTo(0);
                before=fingerprint(bridge,editor);
                editor.replaceText("println 'Modified'");editor.moveTo(0);
                after=fingerprint(bridge,editor);
                if(before.equals(after))throw new AssertionError("Script source changes must invalidate Run approval even at the same caret");
                var check=new CheckMenuItem("Show grid");boolean[] observed={false};
                check.setOnAction(event->observed[0]=check.isSelected());
                LumaExtension.fireMenu(check);
                if(!check.isSelected()||!observed[0])throw new AssertionError("Check command must change state before event handler");
                LumaExtension.fireMenu(check);if(check.isSelected())throw new AssertionError("Check command must toggle off");
                var group=new ToggleGroup();var first=new RadioMenuItem("First");var second=new RadioMenuItem("Second");
                first.setToggleGroup(group);second.setToggleGroup(group);first.setSelected(true);
                LumaExtension.fireMenu(second);
                if(!second.isSelected()||first.isSelected())throw new AssertionError("Radio command must select its group item");
                result.complete(null);
            } catch(Throwable error){result.completeExceptionally(error);}
        });
        try {result.get(20,TimeUnit.SECONDS);System.out.println("JavaFX list/table approval binding and selectable menu dispatch: passed");}
        finally {Platform.exit();}
    }
    private static String fingerprint(LumaExtension bridge,Node control) throws Exception {
        var visit=LumaExtension.class.getDeclaredMethod("visitNode",Node.class,List.class);visit.setAccessible(true);
        var fingerprint=LumaExtension.class.getDeclaredMethod("controlFingerprint",List.class);fingerprint.setAccessible(true);
        var states=new ArrayList<Map<String,Object>>();visit.invoke(bridge,control,states);
        return (String)fingerprint.invoke(bridge,states);
    }
}
