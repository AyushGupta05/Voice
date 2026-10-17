// @recipe bleach-correction
// @title Photobleaching correction for a time-lapse
// @when correct photobleaching / fading in a time-lapse or movie before measuring intensities ("bleach correction", "the signal fades over time", "correct for bleaching")
// @param channel = 1 | channel to correct, 1-based
// @param method = Simple Ratio | "Simple Ratio", "Exponential Fit" or "Histogram Matching"
// @param background = 0 | Simple Ratio only: background intensity subtracted before the ratio
// @output A new corrected time series "<title>-bleach-corrected" (one channel; a z-stack is max-projected per time point first). Reports the whole-frame mean of the first and last frame before and after.
// @say Bleaching corrected: the signal had fallen {dropBefore:%} by the last frame, now {dropAfter:%}.
channel = parseFloat(pennyParam("channel", "1"));
method = pennyParam("method", "Simple Ratio");
background = parseFloat(pennyParam("background", "0"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
series = frames; if (frames == 1) series = slices;
if (series < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=not_a_time_series"); exit; }
run("Select None");
if (channels > 1 || (slices > 1 && frames > 1)) run("Duplicate...", "title=penny-bleach duplicate channels=" + channel);
else run("Duplicate...", "title=penny-bleach duplicate");
if (slices > 1 && frames > 1) { run("Z Project...", "projection=[Max Intensity] all"); close("penny-bleach"); rename("penny-bleach"); }
run("Properties...", "channels=1 slices=1 frames=" + nSlices);
if (bitDepth() == 32 || bitDepth() == 24) run("16-bit");
input = getImageID();
setSlice(1); getStatistics(n, firstBefore); setSlice(nSlices); getStatistics(n, lastBefore);
options = "correction=[" + method + "]";
if (method == "Simple Ratio") options = options + " background=" + background;
run("Bleach Correction", options);
corrected = getImageID();
if (corrected == input) { print("RESULT error=bleach_correction_failed"); exit; }
rename(title + "-bleach-corrected");
setSlice(1); getStatistics(n, firstAfter); setSlice(nSlices); getStatistics(n, lastAfter);
frames = nSlices;
selectImage(input); close();
selectImage(corrected);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT frames=" + frames + " firstBefore=" + d2s(firstBefore, 2) + " lastBefore=" + d2s(lastBefore, 2) + " firstAfter=" + d2s(firstAfter, 2) + " lastAfter=" + d2s(lastAfter, 2) + " dropBefore=" + d2s(100 * (1 - lastBefore / firstBefore), 2) + " dropAfter=" + d2s(100 * (1 - lastAfter / firstAfter), 2) + " method=[" + method + "] channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
