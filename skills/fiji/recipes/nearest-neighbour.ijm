// @recipe nearest-neighbour
// @title Nearest-neighbour distances and clustering
// @when spacing or spatial distribution of cells, nuclei, spots or points: are they clustered, random or regular ("nearest neighbour distance", "how far apart are the cells", "are they clustered")
// @output Results with one row per object (X, Y, NND). Points come from the active multi-point selection, otherwise the ROI Manager centroids. Reports n, the mean/SD/min NND and the Clark–Evans ratio R (about 1 random, below 1 clustered, above 1 regular; over the image or the bounding box of the points, no edge correction).
// @say {n} objects, mean nearest-neighbour distance {meanNND:len}, Clark–Evans ratio {clarkEvans}.

if (nImages == 0) { print("RESULT error=no_image"); exit; }
getPixelSize(unit, pw, ph);
if (selectionType() == 10) { getSelectionCoordinates(xs, ys); }
else {
  // Only this image's objects (tagged with its id, or untagged) take part; other images' objects stay out.
  id = getImageID(); Stack.getPosition(oc, oz, ot);
  xs = newArray(0); ys = newArray(0);
  for (i = 0; i < roiManager("count"); i++) {
    roiManager("select", i); owner = Roi.getProperty("luma.imageId");
    if (owner != "" && owner != "" + id) continue;
    Stack.setPosition(oc, oz, ot); List.setMeasurements;
    px = List.getValue("X") / pw; py = List.getValue("Y") / ph;
    xs = Array.concat(xs, px); ys = Array.concat(ys, py);
  }
  roiManager("deselect"); run("Select None");
  if (xs.length < 2) { print("RESULT error=needs_points_or_roi_manager"); exit; }
}
n = xs.length;
if (n < 2) { print("RESULT error=needs_two_points"); exit; }
nnd = newArray(n);
for (i = 0; i < n; i++) {
  best = 1e30;
  for (j = 0; j < n; j++) if (j != i) { d = sqrt(pow((xs[i] - xs[j]) * pw, 2) + pow((ys[i] - ys[j]) * ph, 2)); if (d < best) best = d; }
  nnd[i] = best;
}
Array.getStatistics(nnd, min, max, mean, std);
areaTotal = getWidth() * getHeight() * pw * ph;
expected = 0.5 / sqrt(n / areaTotal);
run("Clear Results");
for (i = 0; i < n; i++) { setResult("X", i, xs[i] * pw); setResult("Y", i, ys[i] * ph); setResult("NND", i, nnd[i]); }
updateResults();
print("RESULT n=" + n + " meanNND=" + d2s(mean, 3) + " sdNND=" + d2s(std, 3) + " minNND=" + d2s(min, 3) + " clarkEvans=" + d2s(mean / expected, 3) + " unit=" + replace(unit, "\\s", "_"));
