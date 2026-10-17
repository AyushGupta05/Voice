// @recipe line-profile
// @title Intensity profile along a line
// @when intensity profile / plot along a line, width of a structure from its profile (FWHM), how intensity changes across a cell or membrane ("plot the profile", "how wide is this filament", "intensity across this line")
// @param channel = 1 | channel to profile, 1-based
// @param lineWidth = 1 | line width averaged across the line, px
// @output A profile plot window (interactive sessions) and Results with Distance and Value columns. Uses the active line selection; without one, a horizontal line through the image centre. Reports length, min, max, mean, the peak position and its full width at half maximum above the profile minimum.
// @say Profile over {length:len}: peak {max} at {peakAt:len}, width at half maximum {fwhm:len}.
channel = parseFloat(pennyParam("channel", "1"));
lineWidth = parseFloat(pennyParam("lineWidth", "1"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
getDimensions(w, h, channels, slices, frames);
if (channels > 1) Stack.setChannel(channel);
type = selectionType();
if (type < 5 || type > 7) { makeLine(0, floor(h / 2), w - 1, floor(h / 2)); }
run("Line Width...", "line=" + lineWidth);
profile = getProfile();
n = profile.length;
getPixelSize(unit, pw, ph);
List.setMeasurements; length = List.getValue("Length");
step = length / maxOf(n - 1, 1);
Array.getStatistics(profile, min, max, mean, std);
peak = 0; for (i = 1; i < n; i++) if (profile[i] > profile[peak]) peak = i;
half = min + (max - min) / 2;
// ImageJ macro evaluates both sides of &&, so the bounds are checked before indexing.
left = peak; while (left > 0) { if (profile[left - 1] >= half) left--; else break; }
right = peak; while (right < n - 1) { if (profile[right + 1] >= half) right++; else break; }
// Interpolate the half-maximum crossings linearly between samples.
l = left; if (left > 0) l = left - (profile[left] - half) / (profile[left] - profile[left - 1]);
r = right; if (right < n - 1) r = right + (profile[right] - half) / (profile[right] - profile[right + 1]);
// A width needs a real peak and a half-maximum crossing on both sides; otherwise it is not reported (NaN).
fwhm = (r - l) * step;
if (max <= min || (left == 0 && profile[0] >= half) || (right == n - 1 && profile[n - 1] >= half)) fwhm = NaN;
run("Clear Results");
for (i = 0; i < n; i++) { setResult("Distance", i, i * step); setResult("Value", i, profile[i]); }
updateResults();
if (!is("batch mode")) run("Plot Profile");
run("Line Width...", "line=1");
print("RESULT length=" + d2s(length, 3) + " min=" + d2s(min, 2) + " max=" + d2s(max, 2) + " mean=" + d2s(mean, 2) + " peakAt=" + d2s(peak * step, 3) + " fwhm=" + d2s(fwhm, 3) + " samples=" + n + " unit=" + replace(unit, "\\s", "_") + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
