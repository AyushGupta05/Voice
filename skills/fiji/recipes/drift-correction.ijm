// @recipe drift-correction
// @title Drift / registration correction of a stack (SIFT)
// @when correct drift, jitter or stage shift in a time-lapse or align the slices of a stack ("register the stack", "correct the drift", "stabilise the movie", "align these slices", "StackReg")
// @param channel = 1 | channel used for the alignment, 1-based
// @param transformation = Translation | Translation, Rigid, Similarity or Affine
// @param maxError = 25 | largest alignment error accepted, px
// @output A new aligned stack "<title>-aligned" (one channel; a z-stack is max-projected per time point first). Reports the largest drift from the first frame that was removed (maxShift, px) and the final frame's drift (finalShiftX/Y, px; translation part of the SIFT matrices summed over consecutive frames).
// @say Aligned {frames} frames; the largest drift corrected was {maxShift} pixels, {unmatchedPairs} frame pairs unmatched.
channel = parseFloat(pennyParam("channel", "1"));
transformation = pennyParam("transformation", "Translation");
maxError = parseFloat(pennyParam("maxError", "25"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
series = frames; if (frames == 1) series = slices;
if (series < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=not_a_stack"); exit; }
run("Select None");
if (channels > 1 || (slices > 1 && frames > 1)) run("Duplicate...", "title=penny-drift duplicate channels=" + channel);
else run("Duplicate...", "title=penny-drift duplicate");
if (slices > 1 && frames > 1) { run("Z Project...", "projection=[Max Intensity] all"); close("penny-drift"); rename("penny-drift"); }
input = getImageID();
options = "initial_gaussian_blur=1.60 steps_per_scale_octave=3 minimum_image_size=64 maximum_image_size=1024 feature_descriptor_size=4 feature_descriptor_orientation_bins=8 closest/next_closest_ratio=0.92 maximal_alignment_error=" + maxError + " inlier_ratio=0.05 expected_transformation=" + transformation + " interpolate show_transformation_matrix";
// SIFT logs one matrix per consecutive frame pair. Without a Log window (batch sessions) ImageJ prints the log to the
// console, so the console is captured while the plugin runs and combined with the Log window text.
logStart = lengthOf(getInfo("log"));
js = "var S = java.lang.System, old = S.out, buffer = new java.io.ByteArrayOutputStream(); S.setOut(new java.io.PrintStream(buffer, true));"
  + "var failure = ''; try { Packages.ij.IJ.run('Linear Stack Alignment with SIFT', '" + options + "'); } catch (e) { failure = 'SIFT_FAILED ' + e; } finally { S.setOut(old); } failure + buffer.toString();";
console = eval("js", js);
aligned = getImageID();
if (aligned == input || startsWith(console, "SIFT_FAILED") || indexOf(console, "not enough to solve the Model") >= 0) {
  if (aligned != input) { selectImage(aligned); close(); }
  selectImage(input); close(); selectImage(orig);
  if (!wasBatch) setBatchMode("exit and display");
  print("RESULT error=alignment_failed reason=too_few_SIFT_features");
  exit;
}
rename(title + "-aligned");
n = nSlices;
text = substring(getInfo("log"), logStart) + "\n" + console;
lines = split(text, "\n");
// Matrices map each frame onto the previous one; their translations add up to the drift of frame k from frame 1.
dx = 0; dy = 0; maxShift = 0; pairs = 0;
for (k = 0; k < lines.length; k++) {
  i = indexOf(lines[k], "AffineTransform[[");
  if (i < 0) continue;
  values = split(replace(substring(lines[k], i + 17), "[\\[\\] ]", ""), ",");
  if (values.length < 6) continue;
  dx += parseFloat(values[2]); dy += parseFloat(values[5]); pairs++;
  maxShift = maxOf(maxShift, sqrt(dx * dx + dy * dy));
}
selectImage(input); close();
selectImage(aligned);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT frames=" + n + " pairs=" + pairs + " unmatchedPairs=" + maxOf(0, n - 1 - pairs) + " maxShift=" + d2s(maxShift, 2) + " finalShiftX=" + d2s(-dx, 2) + " finalShiftY=" + d2s(-dy, 2) + " transformation=" + transformation + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
