// @recipe coloc2
// @title Coloc 2 colocalisation report
// @when full colocalisation statistics between two channels with Coloc 2: Pearson, Manders tM1/tM2 with Costes thresholds, Spearman, Kendall, Li ICQ, Costes significance ("run Coloc 2", "are these two proteins colocalised", "Costes test")
// @param channelA = 1 | first channel, 1-based
// @param channelB = 2 | second channel, 1-based
// @param randomisations = 10 | Costes significance randomisations (0 = skip the test)
// @param psf = 3 | point-spread-function size for the Costes test, px
// @output The full Coloc 2 report (thresholds, Pearson with/without threshold, Manders M1/M2 and tM1/tM2, Spearman, Kendall, Li ICQ, Costes P) printed to the Log. Uses the active area selection as the region when there is one. Correlation is not interaction.
// @say Coloc 2: Pearson {pearson}, Manders tM1 {tM1} and tM2 {tM2} above the Costes thresholds, Costes P {costesP}.
channelA = parseFloat(pennyParam("channelA", "1"));
channelB = parseFloat(pennyParam("channelB", "2"));
randomisations = parseFloat(pennyParam("randomisations", "10"));
psf = parseFloat(pennyParam("psf", "3"));

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
if (channels < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=needs_two_channels"); exit; }
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
if (area) Roi.copy();
run("Select None");
run("Duplicate...", "title=[penny-coloc-a-" + tag + "] duplicate channels=" + channelA);
if (area) Roi.paste();
selectImage(orig); run("Select None");
run("Duplicate...", "title=[penny-coloc-b-" + tag + "] duplicate channels=" + channelB);
mask = 0; if (area) mask = 1;
costes = "false"; if (randomisations > 0) costes = "true";
// Coloc 2 is driven through its own API: the menu command opens a separate result window and keeps the numbers there.
// java.awt.headless reads true only while it runs so it adds no window; the JVM stays a normal session.
js = "var C = Packages.sc.fiji.coloc.Coloc_2, W = Packages.ij.WindowManager, S = java.lang.System;"
  + "var impA = W.getImage('penny-coloc-a-" + tag + "'), impB = W.getImage('penny-coloc-b-" + tag + "'), c = new C(), previous = S.getProperty('java.awt.headless'), out = '';"
  + "S.setProperty('java.awt.headless', 'true');"
  + "try { if (!c.initializeSettings(impA, impB, " + mask + ", 0, false, false, false, true, true, true, true, true, true, false, " + costes + ", " + psf + ", " + maxOf(randomisations, 1) + ")) throw 'Coloc 2 rejected the settings';"
  + "var field = function (o, name) { var f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); };"
  + "var mi = field(c, 'masks').get(0), values = c.colocalise(field(c, 'img1'), field(c, 'img2'), field(mi, 'roi'), field(mi, 'mask'), null).values();"
  + "for (var i = 0; i < values.size(); i++) { var v = values.get(i); out += v.name + '\\t' + (v.isNumber ? v.number : v.value) + '\\n'; }"
  + "} finally { if (previous == null) S.clearProperty('java.awt.headless'); else S.setProperty('java.awt.headless', previous); } out;";
report = eval("js", js);
print("Coloc 2 report (" + title + ", channel " + channelA + " vs " + channelB + "):");
print(report);
pearson = colocValue(report, "Pearson's R value (no threshold)");
pearsonAbove = colocValue(report, "Pearson's R value (above threshold)");
tM1 = colocValue(report, "Manders' tM1 (Above autothreshold of Ch2)");
tM2 = colocValue(report, "Manders' tM2 (Above autothreshold of Ch1)");
spearman = colocValue(report, "Spearman's rank correlation value");
kendall = colocValue(report, "Kendall's Tau-b rank correlation value");
icq = colocValue(report, "Li's ICQ value");
costesP = colocValue(report, "Costes P-Value");
thresholdA = colocValue(report, "Ch1 Max Threshold");
thresholdB = colocValue(report, "Ch2 Max Threshold");
close("penny-coloc-a-" + tag); close("penny-coloc-b-" + tag);
selectImage(orig);
if (area) Roi.paste();
if (!wasBatch) setBatchMode("exit and display");
print("RESULT pearson=" + pearson + " pearsonAboveThreshold=" + pearsonAbove + " tM1=" + tM1 + " tM2=" + tM2 + " spearman=" + spearman + " kendall=" + kendall + " icq=" + icq + " costesP=" + costesP + " thresholdA=" + thresholdA + " thresholdB=" + thresholdB + " channelA=" + channelA + " channelB=" + channelB);

/** The report has "<name><tab><value>" lines; returns the value rounded to 4 decimals, or NaN when it is absent. */
function colocValue(text, name) {
  lines = split(text, "\n");
  for (k = 0; k < lines.length; k++) if (startsWith(lines[k], name + "\t")) return d2s(parseFloat(substring(lines[k], lengthOf(name) + 1)), 4);
  return "NaN";
}
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
