// Fidelis reference dumper (docs/FIDELIS-VERIFICATION.md). Runs inside the pinned Fiji through the macro call
//   eval("js", "var DUMP_OUT='...'; var DUMP_TAG='...'; var DUMP_RAW=true;" + File.openAsString(path));
// It only READS state; it never changes images, selections, tables or globals.
//
// Output: <DUMP_OUT>/<DUMP_TAG>.json and, for planes up to 1 MiB, <DUMP_OUT>/raw/<DUMP_TAG>/<n>_<plane>.raw.
// Pixel hashing contract (mirrored by sc.fidelis.testkit.PixelHash in Java): SHA-256 over the raw
// little-endian bytes of every plane in stack order (8-bit as stored, 16-bit short LE, 32-bit IEEE-754
// float bits LE, RGB packed ARGB int LE). Numbers are written with Double.toString / Float.toString,
// which round-trip exactly (the macro language's own number-to-string is lossy, 4 decimals).
// ECMAScript 5 only (Rhino).
(function () {
  var Files = java.nio.file.Files, Paths = java.nio.file.Paths;
  var WM = Packages.ij.WindowManager, Prefs = Packages.ij.Prefs;

  function hex(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += ((bytes[i] & 255) + 256).toString(16).substring(1);
    return s;
  }
  function sha(bytes) {
    var md = java.security.MessageDigest.getInstance('SHA-256');
    md.update(bytes);
    return hex(md.digest());
  }
  function str(x) { return x === null || x === undefined ? null : '' + x; }
  function dbl(x) { return '' + java.lang.Double.toString(x); }
  function writeText(path, text) {
    var p = Paths.get(path);
    if (p.getParent() !== null) Files.createDirectories(p.getParent());
    Files.write(p, new java.lang.String(text).getBytes('UTF-8'));
  }
  function writeBytes(path, bytes) {
    var p = Paths.get(path);
    Files.createDirectories(p.getParent());
    Files.write(p, bytes);
  }

  function leBytes(px, bitDepth) {
    if (bitDepth === 8) return px;
    var n = px.length;
    var order = java.nio.ByteOrder.LITTLE_ENDIAN;
    if (bitDepth === 16) {
      var b16 = java.nio.ByteBuffer.allocate(n * 2).order(order);
      b16.asShortBuffer().put(px);
      return b16.array();
    }
    if (bitDepth === 32) {
      var b32 = java.nio.ByteBuffer.allocate(n * 4).order(order);
      b32.asFloatBuffer().put(px);
      return b32.array();
    }
    var b24 = java.nio.ByteBuffer.allocate(n * 4).order(order);
    b24.asIntBuffer().put(px);
    return b24.array();
  }

  function lutHash(imp) {
    var cm = imp.getProcessor().getCurrentColorModel();
    if (cm === null || !(cm instanceof java.awt.image.IndexColorModel)) return null;
    var n = cm.getMapSize();
    var r = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, n);
    var g = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, n);
    var b = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, n);
    cm.getReds(r); cm.getGreens(g); cm.getBlues(b);
    var md = java.security.MessageDigest.getInstance('SHA-256');
    md.update(r); md.update(g); md.update(b);
    return hex(md.digest());
  }

  function floatList(a) {
    var out = [];
    for (var i = 0; i < a.length; i++) out.push('' + java.lang.Float.toString(a[i]));
    return out;
  }

  function roiInfo(roi) {
    if (roi === null) return null;
    var fp = roi.getFloatPolygon();
    var b = roi.getBounds();
    var mask = roi.getMask();
    var maskHash = null;
    if (mask !== null) maskHash = sha(mask.getPixels());
    var info = {
      type: roi.getType() + 0,
      typeName: str(roi.getTypeAsString()),
      name: str(roi.getName()),
      bounds: [b.x + 0, b.y + 0, b.width + 0, b.height + 0],
      position: roi.getPosition() + 0,
      strokeWidth: dbl(roi.getStrokeWidth()),
      xs: floatList(fp.xpoints),
      ys: floatList(fp.ypoints),
      nPoints: fp.npoints + 0,
      maskBounds: mask === null ? null : [mask.getWidth() + 0, mask.getHeight() + 0],
      maskSha256: maskHash
    };
    return info;
  }

  function dumpImage(imp, index, rawDir) {
    var type = imp.getType();
    var bits = imp.getBitDepth();
    var stack = imp.getStack();
    var n = stack.getSize();
    var md = java.security.MessageDigest.getInstance('SHA-256');
    var planes = [];
    for (var i = 1; i <= n; i++) {
      var bytes = leBytes(stack.getPixels(i), bits);
      md.update(bytes);
      planes.push(sha(bytes));
      if (DUMP_RAW && bytes.length <= 1048576) writeBytes(rawDir + '/' + index + '_' + i + '.raw', bytes);
    }
    var cal = imp.getCalibration();
    var ip = imp.getProcessor();
    var coeff = cal.getCoefficients();
    var info = imp.getInfoProperty();
    var roi = imp.getRoi();
    var overlay = imp.getOverlay();
    return {
      index: index,
      title: str(imp.getTitle()),
      type: type + 0,
      bitDepth: bits + 0,
      width: imp.getWidth() + 0,
      height: imp.getHeight() + 0,
      channels: imp.getNChannels() + 0,
      slices: imp.getNSlices() + 0,
      frames: imp.getNFrames() + 0,
      stackSize: n + 0,
      invertedLut: !!imp.isInvertedLut(),
      lutSha256: lutHash(imp),
      displayRange: [dbl(imp.getDisplayRangeMin()), dbl(imp.getDisplayRangeMax())],
      threshold: [dbl(ip.getMinThreshold()), dbl(ip.getMaxThreshold())],
      calibration: {
        pixelWidth: dbl(cal.pixelWidth), pixelHeight: dbl(cal.pixelHeight), pixelDepth: dbl(cal.pixelDepth),
        unit: str(cal.getUnit()), xOrigin: dbl(cal.xOrigin), yOrigin: dbl(cal.yOrigin), zOrigin: dbl(cal.zOrigin),
        frameInterval: dbl(cal.frameInterval), function: cal.getFunction() + 0,
        coefficients: coeff === null ? null : floatListD(coeff), valueUnit: str(cal.getValueUnit()),
        signed16: !!cal.isSigned16Bit()
      },
      infoSha256: info === null ? null : sha(new java.lang.String(info).getBytes('UTF-8')),
      overlayCount: overlay === null ? 0 : overlay.size() + 0,
      planeSha256: planes,
      allPlanesSha256: hex(md.digest()),
      roi: roiInfo(roi)
    };
  }
  function floatListD(a) {
    var out = [];
    for (var i = 0; i < a.length; i++) out.push(dbl(a[i]));
    return out;
  }

  function dumpTable(rt) {
    if (rt === null) return null;
    var headings = rt.getHeadings();
    var rows = rt.size();
    var cols = [];
    for (var h = 0; h < headings.length; h++) {
      var name = '' + headings[h];
      if (name === 'Label') {
        var labels = [];
        for (var r = 0; r < rows; r++) labels.push(str(rt.getLabel(r)));
        cols.push({ name: name, labels: labels });
        continue;
      }
      var ci = rt.getColumnIndex(name);
      var vals = [], hexes = [];
      for (var r2 = 0; r2 < rows; r2++) {
        var v;
        try { v = rt.getValueAsDouble(ci, r2); } catch (e) { v = NaN; }
        vals.push(dbl(v));
        hexes.push('' + java.lang.Double.toHexString(v));
      }
      cols.push({ name: name, values: vals, hex: hexes });
    }
    return { rows: rows + 0, columns: cols };
  }

  function summaryTable() {
    try { return dumpTable(Packages.ij.measure.ResultsTable.getResultsTable('Summary')); }
    catch (e) { return null; }
  }

  function managerRois() {
    if (java.awt.GraphicsEnvironment.isHeadless()) return null; // no ROI Manager without a display
    var rm = Packages.ij.plugin.frame.RoiManager.getInstance2();
    if (rm === null) return null;
    var arr = rm.getRoisAsArray();
    var out = [];
    for (var i = 0; i < arr.length; i++) out.push(roiInfo(arr[i]));
    return out;
  }

  function safe(what, fn) {
    try { return fn(); } catch (e) { return { dumpError: what + ': ' + e }; }
  }
  function main() {
  var ids = WM.getIDList();
  var images = [];
  var rawDir = DUMP_OUT + '/raw/' + DUMP_TAG;
  if (ids !== null) {
    var list = [];
    for (var k = 0; k < ids.length; k++) list.push(ids[k] + 0);
    list.sort(function (a, b) { return b - a; }); // IDs are negative and decrease: creation order
    for (var m = 0; m < list.length; m++) {
      var imp = WM.getImage(list[m]);
      if (imp !== null) images.push(safe('image ' + m, function () { return dumpImage(imp, m, rawDir); }));
    }
  }
  var cur = safe('current', function () { return WM.getCurrentImage(); });
  if (cur && cur.dumpError) cur = null;
  var gl = Packages.ij.plugin.filter.Analyzer;
  var out = {
    dumperVersion: 1,
    tag: DUMP_TAG,
    imagej: str(Packages.ij.IJ.getFullVersion()),
    java: str(java.lang.System.getProperty('java.version')),
    headlessAwt: !!java.awt.GraphicsEnvironment.isHeadless(),
    currentImageTitle: cur === null ? null : str(cur.getTitle()),
    images: images,
    results: safe('results', function () { return dumpTable(gl.getResultsTable()); }),
    summary: safe('summary', summaryTable),
    roiManager: safe('roiManager', managerRois),
    globals: {
      blackBackground: !!Prefs.blackBackground,
      measurements: gl.getMeasurements() + 0,
      precision: gl.getPrecision() + 0,
      doScaling: !!Packages.ij.process.ImageConverter.getDoScaling(),
      weightedColor: !!Prefs.weightedColor,
      calibrateConversions: !!Prefs.calibrateConversions,
      padEdges: !!Prefs.padEdges,
      useInvertingLut: !!Prefs.useInvertingLut
    }
  };
  writeText(DUMP_OUT + '/' + DUMP_TAG + '.json', JSON.stringify(out, null, 1));
  return 'ok';
  }
  try { return main(); } catch (e) { return 'ERR ' + e + ' line ' + e.lineNumber; }
})();
