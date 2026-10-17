#@ String dir
#@ String prefix
#@ String outFile
// P15 - runs each generated P14 macro in-process via IJ.runMacroFile(path, arg),
// capturing System.out/System.err so that the macro interpreter's error text is visible
// (the GUI-subsystem launcher's own stdout/stderr cannot be redirected; see README-G1).
import ij.*
try {
  def out = new File(outFile)
  def esc = { s -> s == null ? '' : s.toString().replace('\t', ' ').replace('\r', ' ').replace('\n', ' | ') }
  out.withWriter('UTF-8') { w ->
    w << "file\treturn\tresult_file\tcaptured_output\n"
    new File(dir).listFiles().findAll { it.name.startsWith(prefix) && it.name.endsWith('.ijm') }.sort { it.name }.each { f ->
      def res = new File(f.path.replace('.ijm', '.inproc.result'))
      res.delete(); new File(res.path + '.started').delete()
      def baos = new ByteArrayOutputStream(); def ps = new PrintStream(baos, true, 'UTF-8')
      def oo = System.out, oe = System.err
      def ret = null
      def th = new Thread({
        try { System.setOut(ps); System.setErr(ps); ret = IJ.runMacroFile(f.path, res.path.replace('\\', '/')) }
        catch (Throwable t) { ret = 'EXC ' + t }
        finally { System.setOut(oo); System.setErr(oe) }
      } as Runnable)
      th.start(); th.join(60000)
      if (th.isAlive()) { System.setOut(oo); System.setErr(oe); ret = 'HANG'; th.interrupt() }
      try { ij.macro.Interpreter.getBatchModeImageIDs()?.each { id -> WindowManager.getImage(id)?.close() } } catch (Throwable t) {}
      def cap = baos.toString('UTF-8'); if (cap.length() > 800) cap = cap.substring(0, 800) + '...'
      w << f.name << "\t" << esc(ret) << "\t" << esc(res.exists() ? res.text : 'NO RESULT FILE') << "\t" << esc(cap) << "\n"; w.flush()
    }
  }
} catch (Throwable top) {
  new File(outFile + '.error').text = top.toString() + '\n' + top.getStackTrace().join('\n')
}
System.exit(0)
