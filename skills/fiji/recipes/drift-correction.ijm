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

// Temporary windows get a per-run suffix, so cleanup by title can never match one of the researcher's images.
tag = "" + getTime() + "-" + floor(random * 100000);
if (nImages == 0) { print("RESULT error=no_image"); exit; }
if (transformation != "Translation" && transformation != "Rigid" && transformation != "Similarity" && transformation != "Affine") { print("RESULT error=unknown_transformation"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
series = frames; if (frames == 1) series = slices;
if (series < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=not_a_stack"); exit; }
run("Select None");
if (channels > 1 || (slices > 1 && frames > 1)) run("Duplicate...", "title=[penny-drift-" + tag + "] duplicate channels=" + channel);
else run("Duplicate...", "title=[penny-drift-" + tag + "] duplicate");
if (slices > 1 && frames > 1) { run("Z Project...", "projection=[Max Intensity] all"); close("penny-drift-" + tag); rename("penny-drift-" + tag); }
input = getImageID();
options = "initial_gaussian_blur=1.60 steps_per_scale_octave=3 minimum_image_size=64 maximum_image_size=1024 feature_descriptor_size=4 feature_descriptor_orientation_bins=8 closest/next_closest_ratio=0.92 maximal_alignment_error=" + maxError + " inlier_ratio=0.05 expected_transformation=" + transformation + " interpolate show_transformation_matrix";
// SIFT logs one matrix per consecutive frame pair. Without a Log window (batch sessions) ImageJ prints the log to the
// console, so the console is captured while the plugin runs and combined with the Log window text.
logStart = lengthOf(getInfo("log"));
// The options travel as a system property, never spliced into the script text.
call("java.lang.System.setProperty", "penny.recipe.options", options);
js = "var S = java.lang.System, old = S.out, buffer = new java.io.ByteArrayOutputStream(); S.setOut(new java.io.PrintStream(buffer, true));"
  + "var failure = ''; try { Packages.ij.IJ.run('Linear Stack Alignment with SIFT', S.getProperty('penny.recipe.options')); } catch (e) { failure = 'SIFT_FAILED ' + e; } finally { S.setOut(old); S.clearProperty('penny.recipe.options'); } failure + buffer.toString();";
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
// Each matrix maps frame k+1 onto frame k. Composing them gives the transform of frame k onto frame 1; the drift is
// how far that transform moves the image centre (for Translation this is the summed translation).
a00 = 1; a01 = 0; a10 = 0; a11 = 1; tx = 0; ty = 0; cx = getWidth() / 2; cy = getHeight() / 2;
maxShift = 0; pairs = 0; dx = 0; dy = 0;
for (k = 0; k < lines.length; k++) {
  i = indexOf(lines[k], "AffineTransform[[");
  if (i < 0) continue;
  m = split(replace(substring(lines[k], i + 17), "[\\[\\] ]", ""), ",");
  if (m.length < 6) continue;
  m00 = parseFloat(m[0]); m01 = parseFloat(m[1]); m02 = parseFloat(m[2]); m10 = parseFloat(m[3]); m11 = parseFloat(m[4]); m12 = parseFloat(m[5]);
  ntx = a00 * m02 + a01 * m12 + tx; nty = a10 * m02 + a11 * m12 + ty;
  n00 = a00 * m00 + a01 * m10; n01 = a00 * m01 + a01 * m11; n10 = a10 * m00 + a11 * m10; n11 = a10 * m01 + a11 * m11;
  a00 = n00; a01 = n01; a10 = n10; a11 = n11; tx = ntx; ty = nty; pairs++;
  dx = a00 * cx + a01 * cy + tx - cx; dy = a10 * cx + a11 * cy + ty - cy;
  maxShift = maxOf(maxShift, sqrt(dx * dx + dy * dy));
}
selectImage(input); close();
selectImage(aligned);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT frames=" + n + " pairs=" + pairs + " unmatchedPairs=" + maxOf(0, n - 1 - pairs) + " maxShift=" + d2s(maxShift, 2) + " finalShiftX=" + d2s(-dx, 2) + " finalShiftY=" + d2s(-dy, 2) + " transformation=" + transformation + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
