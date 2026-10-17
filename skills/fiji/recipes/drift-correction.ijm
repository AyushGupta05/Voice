// @recipe drift-correction
// @title Drift / registration correction of a stack (SIFT)
// @when correct drift, jitter or stage shift in a time-lapse or align the slices of a stack ("register the stack", "correct the drift", "stabilise the movie", "align these slices", "StackReg")
// @param channel = 1 | channel used for the alignment, 1-based
// @param transformation = Translation | Translation, Rigid, Similarity or Affine
// @param maxError = 25 | largest alignment error accepted, px
// @output A new aligned stack "<title>-aligned" (one channel; a z-stack is max-projected per time point first). Reports the largest and the final shift relative to the first frame, in pixels, from the logged transformation matrices.
// @say Aligned {frames} frames; the largest drift corrected was {maxShift} pixels.
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
logStart = lengthOf(getInfo("log"));
run("Linear Stack Alignment with SIFT", "initial_gaussian_blur=1.60 steps_per_scale_octave=3 minimum_image_size=64 maximum_image_size=1024 feature_descriptor_size=4 feature_descriptor_orientation_bins=8 closest/next_closest_ratio=0.92 maximal_alignment_error=" + maxError + " inlier_ratio=0.05 expected_transformation=" + transformation + " interpolate show_transformation_matrix");
aligned = getImageID();
if (aligned == input) { print("RESULT error=alignment_failed"); exit; }
rename(title + "-aligned");
n = nSlices;
// Each logged matrix maps frame k onto frame 1; its translation part is the drift that was removed.
text = substring(getInfo("log"), logStart);
lines = split(text, "\n");
maxShift = 0; lastShift = 0; matrices = 0;
for (k = 0; k < lines.length; k++) {
  i = indexOf(lines[k], "AffineTransform[[");
  if (i < 0) continue;
  values = split(replace(substring(lines[k], i + 17), "[\\[\\] ]", ""), ",");
  if (values.length < 6) continue;
  shift = sqrt(pow(parseFloat(values[2]), 2) + pow(parseFloat(values[5]), 2));
  maxShift = maxOf(maxShift, shift); lastShift = shift; matrices++;
}
selectImage(input); close();
selectImage(aligned);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT frames=" + n + " matrices=" + matrices + " maxShift=" + d2s(maxShift, 2) + " lastShift=" + d2s(lastShift, 2) + " transformation=" + transformation + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
