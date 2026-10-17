package science.luma.qupath;
import java.util.prefs.*;
/** In-memory preferences only; synthetic app never reads or changes the researcher's preference node. */
public final class SyntheticImportPreferences implements PreferencesFactory {
    private final MemoryPreferencesFactory memory=new MemoryPreferencesFactory();
    public SyntheticImportPreferences(){
        String root=System.getProperty("luma.synthetic.root");
        if(root==null)throw new IllegalStateException("Fixture root required");
        memory.userRoot().node(System.getProperty("qupath.prefs.name")).put("userPath",root+"/user");
    }
    public Preferences userRoot(){return memory.userRoot();}
    public Preferences systemRoot(){return memory.systemRoot();}
}
