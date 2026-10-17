// @recipe coloc2
// @title Coloc 2 colocalisation report
// @when full colocalisation statistics between two channels with Coloc 2: Pearson, Manders tM1/tM2 with Costes thresholds, Spearman, Kendall, Li ICQ, Costes significance ("run Coloc 2", "are these two proteins colocalised", "Costes test")
// @param channelA = 1 | first channel, 1-based
// @param channelB = 2 | second channel, 1-based
// @param randomisations = 10 | Costes significance randomisations (0 = skip the test)
// @param psf = 3 | point-spread-function size for the Costes test, px
// @output The full Coloc 2 report in the Log window. Uses the active area selection as the region when there is one. Correlation is not interaction.
// @say Coloc 2: Pearson {pearson} above Costes thresholds, Manders tM1 {tM1}, tM2 {tM2}.
channelA = parseFloat(pennyParam("channelA", "1"));
channelB = parseFloat(pennyParam("channelB", "2"));
randomisations = parseFloat(pennyParam("randomisations", "10"));
psf = parseFloat(pennyParam("psf", "3"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
if (channels < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=needs_two_channels"); exit; }
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
roi = "<None>";
if (area) { getSelectionCoordinates(rx, ry); roi = "ROI(s) in channel 1"; }
run("Select None");
run("Duplicate...", "title=penny-coloc-a duplicate channels=" + channelA);
if (area) makeSelection("polygon", rx, ry);
selectImage(orig); run("Select None");
run("Duplicate...", "title=penny-coloc-b duplicate channels=" + channelB);
selectImage(orig); if (area) makeSelection("polygon", rx, ry);
logStart = lengthOf(getInfo("log"));
options = "channel_1=penny-coloc-a channel_2=penny-coloc-b roi_or_mask=[" + roi + "] threshold_regression=Costes li_histogram_channel_1 li_histogram_channel_2 li_icq spearman's_rank_correlation manders'_correlation kendall's_tau_rank_correlation 2d_instensity_histogram psf=" + psf + " costes_randomisations=" + maxOf(randomisations, 1);
if (randomisations > 0) options = options + " costes'_significance_test";
// Coloc 2 writes its full report to the Log instead of a separate result window while java.awt.headless reads true;
// the JVM itself stays a normal windowed session (the AWT headless state is fixed at startup), so dialogs still work.
previous = eval("js", "var p = java.lang.System.getProperty('java.awt.headless'); java.lang.System.setProperty('java.awt.headless', 'true'); '' + p;");
run("Coloc 2", options);
if (previous == "null") eval("js", "java.lang.System.clearProperty('java.awt.headless'); ''");
else eval("js", "java.lang.System.setProperty('java.awt.headless', '" + previous + "'); ''");
colocLog = substring(getInfo("log"), logStart);
pearson = colocValue(colocLog, "Pearson's R value (above threshold)");
pearsonAll = colocValue(colocLog, "Pearson's R value (no threshold)");
tM1 = colocValue(colocLog, "Manders' tM1 (Above autothreshold of Ch2)");
tM2 = colocValue(colocLog, "Manders' tM2 (Above autothreshold of Ch1)");
spearman = colocValue(colocLog, "Spearman's rank correlation value");
icq = colocValue(colocLog, "Li's ICQ value");
costesP = colocValue(colocLog, "Costes P-Value");
close("penny-coloc-a"); close("penny-coloc-b");
selectImage(orig);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT pearson=" + pearson + " pearsonNoThreshold=" + pearsonAll + " tM1=" + tM1 + " tM2=" + tM2 + " spearman=" + spearman + " icq=" + icq + " costesP=" + costesP + " channelA=" + channelA + " channelB=" + channelB);

/** Coloc 2 logs "<name>, <value>" lines; returns the value as text, or NaN when the line is absent. */
function colocValue(text, name) {
  lines = split(text, "\n");
  for (k = 0; k < lines.length; k++) if (startsWith(lines[k], name + ", ")) return String.trim(substring(lines[k], lengthOf(name) + 2));
  return "NaN";
}
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
