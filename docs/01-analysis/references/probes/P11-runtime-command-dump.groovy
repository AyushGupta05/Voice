#@ String outDir
#@ org.scijava.module.ModuleService moduleService
#@ org.scijava.command.CommandService commandService
#@ org.scijava.script.ScriptService scriptService
#@ org.scijava.plugin.PluginService pluginService
// P11 - runtime command inventory, headless. Writes TSV files into outDir.
import ij.*
import java.awt.GraphicsEnvironment

def d = new File(outDir); d.mkdirs()
def esc = { s -> s == null ? "" : s.toString().replace("\t", "\\t").replace("\n", "\\n") }
def summary = new StringBuilder()
summary << "java.awt.headless=" + GraphicsEnvironment.isHeadless() + "\n"
summary << "IJ.getFullVersion=" + IJ.getFullVersion() + "\n"
summary << "IJ.getInstance()==null: " + (IJ.getInstance() == null) + "\n"
summary << "Menus.getMenuBar()==null: " + (Menus.getMenuBar() == null) + "\n"

// 1. IJ1 command table
def cmds = Menus.getCommands()
summary << "Menus.getCommands().size=" + (cmds == null ? "null" : cmds.size()) + "\n"
new File(d, "P11-ij1-commands.tsv").withWriter("UTF-8") { w ->
  w << "label\tvalue\n"
  cmds.keySet().sort().each { k -> w << esc(k) << "\t" << esc(cmds.get(k)) << "\n" }
}
// 2. menu structure captured by ij1-patcher hooks (works headless)
def hooks = IJ.class.getField("_hooks").get(null)
def ms = hooks.getMenuStructure()
summary << "hooks.class=" + hooks.getClass().getName() + "\n"
summary << "menuStructure.size=" + ms.size() + "\n"
new File(d, "P11-ij1-menu-structure.tsv").withWriter("UTF-8") { w ->
  w << "menu_path\tcommand\n"
  ms.each { k, v -> w << esc(k) << "\t" << esc(v) << "\n" }
}
// 3. shortcuts
def sc = Menus.getShortcuts()
summary << "Menus.getShortcuts().size=" + (sc == null ? "null" : sc.size()) + "\n"
new File(d, "P11-ij1-shortcuts.tsv").withWriter("UTF-8") { w ->
  w << "keycode\tcommand\n"
  sc?.each { k, v -> w << esc(k) << "\t" << esc(v) << "\n" }
}
// 4. SciJava modules
def mods = moduleService.getModules()
summary << "moduleService.getModules().size=" + mods.size() + "\n"
def byInfo = [:].withDefault { 0 }
new File(d, "P11-modules.tsv").withWriter("UTF-8") { w ->
  w << "identifier\tinfo_class\tdelegate_class\tmenu_root\tmenu_path\ttitle\tcan_run_headless\tvisible\tpriority\tno_legacy\taccelerator\n"
  mods.each { m ->
    byInfo[m.getClass().getName()]++
    def mp = m.getMenuPath()
    def mps = mp == null ? "" : mp.collect { it.getName() }.join(">")
    def acc = (mp != null && mp.getLeaf() != null && mp.getLeaf().getAccelerator() != null) ? mp.getLeaf().getAccelerator().toString() : ""
    def vis = ""
    try { vis = (m instanceof org.scijava.command.CommandInfo && m.getAnnotation() != null) ? m.getAnnotation().visible() : "" } catch (Throwable t) { vis = "err" }
    def deleg = ""
    try { deleg = m.getDelegateClassName() } catch (Throwable t) { deleg = "err:" + t }
    w << esc(m.getIdentifier()) << "\t" << m.getClass().getName() << "\t" << esc(deleg) << "\t" << esc(m.getMenuRoot()) << "\t" << esc(mps) << "\t" << esc(m.getTitle()) << "\t" << m.canRunHeadless() << "\t" << vis << "\t" << m.getPriority() << "\t" << m.is("no-legacy") << "\t" << esc(acc) << "\n"
  }
}
summary << "modules by info class=" + byInfo + "\n"
summary << "commandService.getCommands().size=" + commandService.getCommands().size() + "\n"
summary << "scriptService.getScripts().size=" + scriptService.getScripts().size() + "\n"
summary << "scriptService.getScriptDirectories=" + scriptService.getScriptDirectories() + "\n"
summary << "scriptLanguages=" + scriptService.getLanguages().collect { it.getLanguageName() + ":" + it.getExtensions() } + "\n"
summary << "pluginService.getPlugins().size=" + pluginService.getPlugins().size() + "\n"
new File(d, "P11-summary.txt").setText(summary.toString(), "UTF-8")
