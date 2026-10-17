#@ String outFile
#@ String tmpDir
#@ String batch
// P13 - headless behaviour probe: IJ.run(imp, command, options) on synthetic images.
// batch=true sets ij.macro.Interpreter.batchMode=true so that images shown by
// commands are registered (ImagePlus.show -> Interpreter.addBatchModeImage).
import ij.*
import ij.process.*
import ij.gui.*
import ij.measure.ResultsTable
import ij.plugin.filter.Analyzer
import ij.plugin.frame.RoiManager
import ij.macro.Interpreter

Interpreter.batchMode = batch.toBoolean()

def blobs = { ->
  def imp = IJ.createImage("blobs", "8-bit black", 256, 256, 1)
  def ip = imp.getProcessor(); ip.setValue(220)
  ip.fillOval(10, 10, 60, 60); ip.fillOval(100, 40, 40, 40); ip.fillOval(165, 145, 70, 70); ip.fillOval(35, 165, 50, 50)
  imp
}
def stack3 = { -> IJ.createImage("stack", "8-bit ramp", 128, 128, 3) }
def cks = { ImagePlus im ->
  if (im == null) return -1
  def ip = im.getProcessor(); long s = 0
  for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) s += (long) (ip.getPixelValue(x, y) * (x + 1) * (y + 3)) % 9973
  s
}
def nbins = { ImagePlus im -> im.getProcessor().getHistogram().findAll { it > 0 }.size() }
def rt = { -> ResultsTable.getResultsTable() }

// each op: id, cmd, opts, setup (returns imp), pre (imp->), check (imp, cur, ctx) -> [ok, detail]
def ops = []
def op = { m -> ops << m }
op(id: '01', cmd: 'Invert', opts: '', setup: blobs, check: { i, c, x -> [cks(i) != x.c0, "cks ${x.c0}->${cks(i)}"] })
op(id: '02', cmd: 'Gaussian Blur...', opts: 'sigma=2', setup: blobs, check: { i, c, x -> [cks(i) != x.c0, "cks ${x.c0}->${cks(i)}"] })
op(id: '03', cmd: 'Subtract Background...', opts: 'rolling=50', setup: { -> def i = blobs(); i.getProcessor().add(20); i },
   check: { i, c, x -> def m = i.getStatistics().mean; [m < x.m0, "mean ${x.m0}->${m}"] })
op(id: '04', cmd: 'Median...', opts: 'radius=2', setup: { -> def i = blobs(); def ip = i.getProcessor(); ip.putPixel(128, 5, 255); ip.putPixel(5, 128, 255); ip.putPixel(250, 250, 255); i }, check: { i, c, x -> [cks(i) != x.c0, "cks ${x.c0}->${cks(i)}"] })
op(id: '05', cmd: 'Convert to Mask', opts: '', setup: blobs, pre: { i -> IJ.setAutoThreshold(i, 'Default dark') },
   check: { i, c, x -> [nbins(i) == 2 && i.getBitDepth() == 8, "bins=${nbins(i)} invertedLut=${i.isInvertedLut()}"] })
op(id: '06', cmd: 'Auto Threshold', opts: 'method=Otsu white', setup: blobs, check: { i, c, x -> [nbins(i) == 2, "bins=${nbins(i)}"] })
op(id: '07', cmd: '(API) IJ.setAutoThreshold', opts: 'Otsu dark', setup: blobs, api: { i -> IJ.setAutoThreshold(i, 'Otsu dark') },
   check: { i, c, x -> def lo = i.getProcessor().getMinThreshold(); [lo != ImageProcessor.NO_THRESHOLD, "min=${lo} max=${i.getProcessor().getMaxThreshold()}"] })
op(id: '08', cmd: 'Set Measurements...', opts: 'area mean min centroid redirect=None decimal=3', setup: blobs,
   check: { i, c, x -> def m = Analyzer.getMeasurements(); def want = Analyzer.AREA | Analyzer.MEAN | Analyzer.MIN_MAX | Analyzer.CENTROID; [m == want, "measurements=${m} want=${want}"] })
op(id: '09', cmd: 'Measure', opts: '', setup: blobs, check: { i, c, x -> def t = rt(); [t != null && t.size() == 1, "rows=${t?.size()} headings=${t?.getColumnHeadings()?.trim()}"] })
op(id: '10', cmd: 'Analyze Particles...', opts: 'size=0-Infinity display clear', setup: blobs, pre: { i -> i.getProcessor().setThreshold(128, 255, ImageProcessor.NO_LUT_UPDATE) },
   check: { i, c, x -> def t = rt(); [t != null && t.size() == 4, "rows=${t?.size()}"] })
op(id: '11', cmd: 'Analyze Particles...', opts: 'size=0-Infinity clear add', setup: blobs, pre: { i -> i.getProcessor().setThreshold(128, 255, ImageProcessor.NO_LUT_UPDATE) },
   check: { i, c, x -> def rm = RoiManager.getInstance(); def rm2 = RoiManager.getRoiManager(); [rm2 != null && rm2.getCount() == 4, "getInstance=${rm} getRoiManager.count=${rm2?.getCount()}"] })
op(id: '12', cmd: 'Histogram', opts: '', setup: blobs, check: { i, c, x -> [c != null && c != i, "current=${c?.getTitle()}"] })
op(id: '13', cmd: 'Duplicate...', opts: 'title=dup', setup: blobs, check: { i, c, x -> [c != null && c.getTitle() == 'dup', "current=${c?.getTitle()}"] })
op(id: '14', cmd: 'Crop', opts: '', setup: blobs, pre: { i -> i.setRoi(10, 10, 100, 80) },
   check: { i, c, x -> def t = (c ?: i); [t.getWidth() == 100 && t.getHeight() == 80, "imp=${i.getWidth()}x${i.getHeight()} current=${c?.getTitle()} ${c?.getWidth()}x${c?.getHeight()}"] })
op(id: '15', cmd: 'Scale...', opts: 'x=0.5 y=0.5 interpolation=Bilinear average create title=scaled', setup: blobs,
   check: { i, c, x -> [c != null && c.getTitle() == 'scaled' && c.getWidth() == 128, "current=${c?.getTitle()} w=${c?.getWidth()}"] })
op(id: '16', cmd: 'Rotate... ', opts: 'angle=15 grid=1 interpolation=Bilinear', setup: blobs, check: { i, c, x -> [cks(i) != x.c0, "cks ${x.c0}->${cks(i)}"] })
op(id: '16b', cmd: 'Rotate...', opts: 'angle=15', setup: blobs, check: { i, c, x -> [cks(i) != x.c0 || i.getRoi() != null, "Edit>Selection>Rotate... without ROI; cks ${x.c0}->${cks(i)} roi=${i.getRoi()}"] })
op(id: '17', cmd: 'Z Project...', opts: 'projection=[Max Intensity]', setup: stack3, check: { i, c, x -> [c != null && c.getTitle().startsWith('MAX_'), "current=${c?.getTitle()}"] })
op(id: '18', cmd: 'Make Substack...', opts: 'slices=1-2', setup: stack3, check: { i, c, x -> [c != null && c != i && c.getStackSize() == 2, "current=${c?.getTitle()} n=${c?.getStackSize()}"] })
op(id: '19', cmd: 'Split Channels', opts: '', setup: { -> IJ.createImage('rgb', 'RGB ramp', 128, 128, 1) }, check: { i, c, x -> [c != null && c.getTitle().endsWith('(blue)'), "current=${c?.getTitle()}"] })
op(id: '20', cmd: 'Merge Channels...', opts: 'c1=r c2=g create', setup: { ->
     def r = IJ.createImage('r', '8-bit ramp', 64, 64, 1); def g = IJ.createImage('g', '8-bit black', 64, 64, 1); r.show(); g.show(); g },
   check: { i, c, x -> [c != null && c.isComposite() && c.getNChannels() == 2, "current=${c?.getTitle()} composite=${c?.isComposite()} ids=${WindowManager.getIDList()}"] })
op(id: '21', cmd: 'Enhance Contrast...', opts: 'saturated=0.35 normalize', setup: { -> def i = blobs(); i.getProcessor().multiply(0.5); i }, check: { i, c, x -> [cks(i) != x.c0, "cks ${x.c0}->${cks(i)}"] })
op(id: '22', cmd: 'Apply LUT', opts: '', setup: blobs, pre: { i -> i.setDisplayRange(50, 150) }, check: { i, c, x -> [cks(i) != x.c0, "cks ${x.c0}->${cks(i)}"] })
op(id: '23', cmd: 'Fill', opts: 'slice', setup: blobs, pre: { i -> i.setRoi(200, 10, 30, 30); IJ.setForegroundColor(255, 255, 255) },
   check: { i, c, x -> def v = i.getProcessor().getPixel(210, 20); [v == 255, "pixel(210,20)=${v}"] })
op(id: '23b', cmd: 'Fill', opts: 'slice', setup: { -> def i = IJ.createImage('z', '8-bit black', 64, 64, 1); i }, pre: { i -> i.setRoi(5, 5, 20, 20); IJ.setForegroundColor(255, 255, 255) },
   check: { i, c, x -> def v = i.getProcessor().getPixel(10, 10); [v == 255, "pixel(10,10)=${v}"] })
op(id: '24', cmd: 'Clear', opts: 'slice', setup: blobs, pre: { i -> i.setRoi(10, 10, 30, 30); IJ.setBackgroundColor(0, 0, 0) },
   check: { i, c, x -> def v = i.getProcessor().getPixel(30, 30); [v == 0, "pixel(30,30)=${v}"] })
op(id: '25', cmd: 'Add to Manager', opts: '', setup: blobs, pre: { i -> i.setRoi(10, 10, 60, 60) },
   post: { i -> def rm = RoiManager.getRoiManager(); rm.runCommand(i, 'Measure') },
   check: { i, c, x -> def rm = RoiManager.getRoiManager(); def t = rt(); [rm != null && rm.getCount() == 1 && t.size() == 1, "rm.count=${rm?.getCount()} rows=${t?.size()}"] })
op(id: '26', cmd: 'Brightness/Contrast...', opts: '', setup: blobs, check: { i, c, x -> [false, "frame command; no measurable effect expected; display range ${i.getDisplayRangeMin()}-${i.getDisplayRangeMax()}"] })
op(id: '27', cmd: 'Threshold...', opts: '', setup: blobs, check: { i, c, x -> [false, "frame command; isThreshold=${i.isThreshold()}"] })
op(id: '28', cmd: 'Undo', opts: '', setup: blobs, pre: { i -> IJ.run(i, 'Invert', '') },
   check: { i, c, x -> [cks(i) == x.c00, "orig=${x.c00} afterInvert=${x.c0} afterUndo=${cks(i)}"] })
op(id: '29', cmd: '8-bit', opts: '', setup: { -> IJ.createImage('f', '32-bit ramp', 64, 64, 1) }, check: { i, c, x -> [i.getBitDepth() == 8, "bitDepth=${i.getBitDepth()}"] })
op(id: '30a', cmd: 'Tiff...', opts: "path=[${tmpDir}/p13-${batch}.tif]", setup: blobs,
   check: { i, c, x -> def f = new File("${tmpDir}/p13-${batch}.tif"); [f.exists() && f.length() > 0, "exists=${f.exists()} size=${f.length()}"] })
op(id: '30b', cmd: 'Open...', opts: "path=[${tmpDir}/p13-${batch}.tif]", setup: { -> null },
   check: { i, c, x -> [c != null && c.getTitle() == "p13-${batch}.tif", "current=${c?.getTitle()}"] })
op(id: '31', cmd: 'Find Maxima...', opts: 'prominence=10 output=Count', setup: blobs,
   check: { i, c, x -> def t = rt(); def n = (t != null && t.size() > 0 && t.columnExists('Count')) ? t.getValue('Count', t.size() - 1) : -1; [n >= 1, "Count=${n}"] })
op(id: '32', cmd: 'Image Calculator...', opts: 'image1=a operation=Add image2=b create', setup: { ->
     def a = IJ.createImage('a', '8-bit ramp', 64, 64, 1); def b = IJ.createImage('b', '8-bit ramp', 64, 64, 1); a.show(); b.show(); b },
   check: { i, c, x -> [c != null && c.getTitle().startsWith('Result of'), "current=${c?.getTitle()}"] })
op(id: '33', cmd: 'Set Scale...', opts: 'distance=10 known=1 unit=um', setup: blobs,
   check: { i, c, x -> def cal = i.getCalibration(); [Math.abs(cal.pixelWidth - 0.1) < 1e-9, "pixelWidth=${cal.pixelWidth} unit=${cal.getUnit()}"] })
op(id: '34', cmd: 'Add Slice', opts: '', setup: stack3, check: { i, c, x -> [i.getStackSize() == 4, "n=${i.getStackSize()}"] })
op(id: '35', cmd: 'Histogram', opts: 'bins=256 use x_min=0 x_max=256 y_max=Auto stack', setup: stack3, check: { i, c, x -> [c != null && c != i, "current=${c?.getTitle()}"] })

def esc = { s -> s == null ? '' : s.toString().replace('\t', ' ').replace('\r', ' ').replace('\n', ' | ') }
def out = new File(outFile)
out.withWriter('UTF-8') { w ->
  w << "mode\tid\tcommand\toptions\tstatus\tproduced\texception\tdetail\tcaptured_output\tms\n"
  ops.each { o ->
    // reset shared state
    try { def ids = WindowManager.getIDList(); ids?.each { id -> def im = WindowManager.getImage(id); if (im != null) { im.changes = false; im.close() } } } catch (Throwable t) {}
    try { Interpreter.getBatchModeImageIDs()?.each { id -> def im = WindowManager.getImage(id); im?.close() } } catch (Throwable t) {}
    try { rt()?.reset() } catch (Throwable t) {}
    try { RoiManager.getInstance()?.reset() } catch (Throwable t) {}
    def ctx = [:]
    def imp = null; def cur = null; Throwable exc = null; Throwable chkExc = null
    def baos = new ByteArrayOutputStream(); def ps = new PrintStream(baos, true, 'UTF-8')
    def oldOut = System.out, oldErr = System.err
    long t0 = System.currentTimeMillis()
    def th = new Thread({
      try {
        imp = o.setup()
        if (imp != null) { ctx.c00 = cks(imp); ctx.m0 = imp.getStatistics().mean }
        if (o.pre) o.pre(imp)
        if (imp != null) ctx.c0 = cks(imp)
        System.setOut(ps); System.setErr(ps)
        if (o.api) o.api(imp) else if (imp == null) IJ.run(o.cmd as String, o.opts as String) else IJ.run(imp, o.cmd as String, o.opts as String)
        if (o.post) o.post(imp)
      } catch (Throwable e) { exc = e }
      finally { System.setOut(oldOut); System.setErr(oldErr) }
      cur = WindowManager.getCurrentImage()
    } as Runnable, 'P13-' + o.id)
    th.start(); th.join(60000)
    def hung = th.isAlive()
    if (hung) { System.setOut(oldOut); System.setErr(oldErr); th.interrupt() }
    def res = [false, '']
    if (!hung) { try { res = o.check(imp, cur, ctx) } catch (Throwable e) { chkExc = e; res = [false, 'check failed: ' + e] } }
    def status = hung ? 'hang' : (exc != null ? 'fail' : (res[0] ? 'pass' : 'fail'))
    def excs = exc == null ? '' : (exc.getClass().getName() + ': ' + exc.getMessage())
    def cap = baos.toString('UTF-8'); if (cap.length() > 600) cap = cap.substring(0, 600) + '...'
    w << "groovy-batch=${batch}\t${o.id}\t${o.cmd}\t${o.opts}\t${status}\t${res[0]}\t${esc(excs)}\t${esc(res[1])}\t${esc(cap)}\t${System.currentTimeMillis() - t0}\n"
    w.flush()
  }
}
// the launcher JVM does not exit by itself after --run when AWT/ImageJ threads remain
System.exit(0)
