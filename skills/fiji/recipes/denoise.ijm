// @recipe denoise
// @title Edge-preserving denoising
// @when denoise or smooth a noisy image while keeping edges sharp ("denoise this", "reduce the noise", "Kuwahara", "anisotropic diffusion", "ROF / total variation")
// @param method = kuwahara | kuwahara (Kuwahara Filter), anisotropic (Anisotropic Diffusion 2D), rof (ROF total-variation denoise) or median
// @param size = 5 | kuwahara: odd sampling window, px; median: radius, px
// @param strength = 25 | rof: theta (higher = smoother); anisotropic: number of iterations
// @output A new image "<title>-denoised" (current channel, slice and frame); the original is unchanged. Reports a noise estimate (standard deviation of the difference from a 1-px median) before and after.
// @say Denoised with {method}; noise estimate went from {noiseBefore} to {noiseAfter}.
method = toLowerCase(pennyParam("method", "kuwahara"));
size = parseFloat(pennyParam("size", "5"));
strength = parseFloat(pennyParam("strength", "25"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
run("Select None");
run("Duplicate...", "title=[" + title + "-denoised]");
out = getImageID();
if (bitDepth() == 24) run("8-bit");
noiseBefore = noise(out);
if (method == "kuwahara") { if (size % 2 == 0) size = size + 1; run("Kuwahara Filter", "sampling=" + size); }
else if (method == "anisotropic") { if (bitDepth() == 32) run("16-bit"); run("Anisotropic Diffusion 2D", "number=" + round(strength) + " smoothings=1 keep=" + round(strength) + " a1=0.50 a2=0.90 dt=20 edge=5"); }
else if (method == "rof") { run("32-bit"); run("ROF Denoise", "theta=" + strength); }
else if (method == "median") run("Median...", "radius=" + size);
else { print("RESULT error=unknown_method method=" + method); exit; }
result = getImageID();
if (result != out) { selectImage(out); close(); selectImage(result); rename(title + "-denoised"); out = result; }
noiseAfter = noise(out);
selectImage(out);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT method=" + method + " noiseBefore=" + d2s(noiseBefore, 3) + " noiseAfter=" + d2s(noiseAfter, 3) + " size=" + size + " strength=" + strength);

/** Noise estimate: standard deviation of the image minus its 1-px median (robust to smooth structure). */
function noise(id) {
  selectImage(id);
  run("Duplicate...", "title=penny-noise-a"); run("32-bit");
  run("Duplicate...", "title=penny-noise-b"); run("Median...", "radius=1");
  imageCalculator("Subtract 32-bit", "penny-noise-a", "penny-noise-b");
  getStatistics(n, mean, min, max, std);
  close("penny-noise-b"); close("penny-noise-a");
  if (isOpen("Result of penny-noise-a")) { selectImage("Result of penny-noise-a"); getStatistics(n, mean, min, max, std); close(); }
  selectImage(id);
  return std;
}
function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
