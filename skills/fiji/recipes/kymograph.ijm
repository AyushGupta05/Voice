// @recipe kymograph
// @title Kymograph along a line
// @when kymograph / space-time plot of movement along a line in a time-lapse: axonal transport, comets, flow, cell edge movement ("make a kymograph", "show movement along this line over time")
// @param channel = 1 | channel to use, 1-based
// @param lineWidth = 3 | line width averaged across the line, px
// @output A new kymograph image "<title>-kymograph": x = distance along the line, y = time (one row per frame). Uses the active line selection (straight, segmented or freehand); without one, a horizontal line through the image centre. A z-stack is max-projected per time point first.
// @say Kymograph made: {length:len} along the line over {frames} frames.
channel = parseFloat(pennyParam("channel", "1"));
lineWidth = parseFloat(pennyParam("lineWidth", "3"));

if (nImages == 0) { print("RESULT error=no_image"); exit; }
orig = getImageID(); title = getTitle();
wasBatch = is("batch mode"); setBatchMode(true);
getDimensions(w, h, channels, slices, frames);
series = frames; if (frames == 1) series = slices;
if (series < 2) { if (!wasBatch) setBatchMode("exit and display"); print("RESULT error=not_a_time_series"); exit; }
type = selectionType();
if (type >= 5 && type <= 7) { getSelectionCoordinates(lx, ly); }
else { lx = newArray(0, w - 1); ly = newArray(floor(h / 2), floor(h / 2)); type = 5; }
run("Select None");
if (channels > 1 || (slices > 1 && frames > 1)) run("Duplicate...", "title=penny-kymo duplicate channels=" + channel);
else run("Duplicate...", "title=penny-kymo duplicate");
if (slices > 1 && frames > 1) { run("Z Project...", "projection=[Max Intensity] all"); close("penny-kymo"); rename("penny-kymo"); }
movie = getImageID();
if (lx.length == 2) makeLine(lx[0], ly[0], lx[1], ly[1]); else makeSelection("polyline", lx, ly);
run("Line Width...", "line=" + lineWidth);
List.setMeasurements;
length = List.getValue("Length");
getPixelSize(unit, pw, ph);
run("Multi Kymograph", "linewidth=" + lineWidth);
kymo = getImageID();
if (kymo == movie) { print("RESULT error=kymograph_failed"); exit; }
rename(title + "-kymograph");
kw = getWidth(); kh = getHeight();
selectImage(movie); close();
run("Line Width...", "line=1");
selectImage(orig);
if (lx.length == 2) makeLine(lx[0], ly[0], lx[1], ly[1]); else makeSelection("polyline", lx, ly);
selectImage(kymo);
if (!wasBatch) setBatchMode("exit and display");
print("RESULT length=" + d2s(length, 2) + " unit=" + replace(unit, " ", "_") + " frames=" + kh + " width=" + kw + " channel=" + channel + " lineWidth=" + lineWidth);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
