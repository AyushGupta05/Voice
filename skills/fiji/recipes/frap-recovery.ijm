// @recipe frap-recovery
// @title FRAP recovery: mobile fraction and half-time
// @when fluorescence recovery after photobleaching: mobile / immobile fraction, recovery half-time, diffusion of a bleached spot ("analyse the FRAP", "how fast does it recover", "mobile fraction")
// @param channel = 1 | channel with the bleached signal, 1-based
// @param bleachFrame = 0 | first frame after the bleach (0 = detect the largest drop)
// @output Results with one row per frame (Time, raw ROI mean, reference mean, double-normalised intensity). Uses the active area selection as the bleached region (required); the rest of the frame is the acquisition-bleaching reference. Fits y = a·(1 − e^(−b·t)) + c to the recovery.
// @say Mobile fraction {mobileFraction:%}, recovery half-time {halfTime} {timeUnit}.
channel = parseFloat(pennyParam("channel", "1"));
bleachFrame = parseFloat(pennyParam("bleachFrame", "0"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
area = selectionType() >= 0 && selectionType() < 5 || selectionType() == 9;
if (!area) { print("RESULT error=needs_bleach_region_selection"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
// Roi.copy/Roi.paste keep composite selections (holes, several pieces) exactly.
Roi.copy();
getDimensions(w, h, channels, slices, frames);
n = frames; if (frames == 1) n = slices;
if (n < 4) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=not_a_time_series"); exit; }
interval = Stack.getFrameInterval(); Stack.getUnits(xu, yu, zu, timeUnit, vu);
if (interval <= 0) { interval = 1; timeUnit = "frames"; }
roi = newArray(n); ref = newArray(n);
Stack.getPosition(c0, z0, t0);
for (t = 1; t <= n; t++) {
  if (frames > 1) Stack.setPosition(channel, z0, t); else Stack.setPosition(channel, t, 1);
  Roi.paste(); getStatistics(roiPixels, roiMean);
  run("Select None"); getStatistics(allPixels, allMean);
  roi[t - 1] = roiMean; ref[t - 1] = (allMean * allPixels - roiMean * roiPixels) / (allPixels - roiPixels);
}
Stack.setPosition(c0, z0, t0);
Roi.paste();
if (bleachFrame < 2) { bleachFrame = 2; drop = 0; for (t = 1; t < n; t++) if (roi[t - 1] - roi[t] > drop) { drop = roi[t - 1] - roi[t]; bleachFrame = t + 1; } }
pre = 0; preRef = 0;
for (t = 0; t < bleachFrame - 1; t++) { pre += roi[t]; preRef += ref[t]; }
pre /= bleachFrame - 1; preRef /= bleachFrame - 1;
norm = newArray(n);
for (t = 0; t < n; t++) norm[t] = (roi[t] / ref[t]) * (preRef / pre);
m = n - bleachFrame + 1;
x = newArray(m); y = newArray(m);
for (k = 0; k < m; k++) { x[k] = k * interval; y[k] = norm[bleachFrame - 1 + k]; }
Fit.doFit("Exponential Recovery", x, y);
a = Fit.p(0); b = Fit.p(1); c = Fit.p(2); r2 = Fit.rSquared;
post = c; plateau = a + c;
mobile = (plateau - post) / (1 - post);
halfTime = log(2) / b;
run("Clear Results");
for (t = 0; t < n; t++) { setResult("Time", t, t * interval); setResult("ROI mean", t, roi[t]); setResult("Reference mean", t, ref[t]); setResult("Normalised", t, norm[t]); }
updateResults();
if (!wasBatch) setBatchMode("exit and display");
print("RESULT mobileFraction=" + d2s(mobile, 4) + " immobileFraction=" + d2s(1 - mobile, 4) + " halfTime=" + d2s(halfTime, 3) + " timeUnit=" + replace(timeUnit, "\\s", "_") + " bleachFrame=" + bleachFrame + " postBleach=" + d2s(post, 4) + " plateau=" + d2s(plateau, 4) + " rSquared=" + d2s(r2, 4) + " frames=" + n);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
