// @recipe pairwise-stitch
// @title Stitch two overlapping tiles (Pairwise stitching)
// @when stitch, mosaic or register two overlapping fields of view / tiles into one image ("stitch these two images", "join the tiles", "how are these two fields offset")
// @param second = auto | title of the second image (auto = the other open image when exactly two are open)
// @param fusion = Linear Blending | fusion method: "Linear Blending", "Average", "Median", "Max. Intensity", "Min. Intensity" or "Overlay into composite image"
// @output A new fused image "<first><-><second>". Reports the offset of the second image relative to the active (first) image in pixels and the cross-correlation R of the overlap from the Stitching plugin.
// @say Stitched with the second image offset {dx}, {dy} pixels, correlation {r}.
second = pennyParam("second", "auto");
fusion = pennyParam("fusion", "Linear Blending");

if (nImages < 2) { print("RESULT error=needs_two_open_images"); exit; }
fusions = newArray("Linear Blending", "Average", "Median", "Max. Intensity", "Min. Intensity", "Overlay into composite image");
known = false; for (k = 0; k < fusions.length; k++) if (fusion == fusions[k]) known = true;
if (!known) { print("RESULT error=unknown_fusion_method"); exit; }
first = getTitle(); firstId = getImageID();
if (second == "auto") {
  if (nImages != 2) { print("RESULT error=name_the_second_image"); exit; }
  for (k = 1; k <= nImages; k++) { selectImage(k); if (getImageID() != firstId) second = getTitle(); }
  selectImage(firstId);
}
if (!isOpen(second)) { print("RESULT error=second_image_not_open"); exit; }
fused = first + "<->" + second;
options = "first_image=[" + first + "] second_image=[" + second + "] fusion_method=[" + fusion + "] fused_image=[" + fused + "] check_peaks=5 compute_overlap subpixel_accuracy x=0.0000 y=0.0000 registration_channel_image_1=[Average all channels] registration_channel_image_2=[Average all channels]";
// The plugin logs the shift; without a Log window (batch sessions) the log goes to the console, so both are read.
logStart = lengthOf(getInfo("log"));
// The options (with image titles) travel as a system property, never spliced into the script text.
call("java.lang.System.setProperty", "penny.recipe.options", options);
js = "var S = java.lang.System, old = S.out, buffer = new java.io.ByteArrayOutputStream(); S.setOut(new java.io.PrintStream(buffer, true));"
  + "var failure = ''; try { Packages.ij.IJ.run('Pairwise stitching', S.getProperty('penny.recipe.options')); } catch (e) { failure = 'FAILED ' + e; } finally { S.setOut(old); S.clearProperty('penny.recipe.options'); } failure + buffer.toString();";
console = eval("js", js);
text = substring(getInfo("log"), logStart) + "\n" + console;
lines = split(text, "\n");
dx = NaN; dy = NaN; r = NaN;
for (k = 0; k < lines.length; k++) {
  i = indexOf(lines[k], "(second relative to first): (");
  if (i < 0) continue;
  rest = substring(lines[k], i + 29);
  coords = split(substring(rest, 0, indexOf(rest, ")")), ", ");
  dx = parseFloat(coords[0]); dy = parseFloat(coords[1]);
  j = indexOf(rest, "correlation (R)=");
  if (j >= 0) { tail = substring(rest, j + 16); r = parseFloat(substring(tail, 0, indexOf(tail + " ", " "))); }
}
if (isNaN(dx)) { print("RESULT error=stitching_failed"); exit; }
print("RESULT dx=" + d2s(dx, 2) + " dy=" + d2s(dy, 2) + " r=" + d2s(r, 4) + " fused=[" + fused + "]");

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
