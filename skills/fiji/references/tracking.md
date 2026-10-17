# Cell tracking over time: TrackMate by script, and a macro fallback

"Track the cells over time", "follow the nuclei", "how fast do they move", "how many cells are moving" all mean: detect objects in every frame, link them across frames, show the tracks on the image, and report counts, lengths and speeds. Fiji's tool for this is [TrackMate](https://imagej.net/plugins/trackmate/) (installed: TrackMate 8.1.6, `Plugins ▷ Tracking ▷ TrackMate`). Its wizard is interactive; from Penny it runs through its scripting API with `fiji_run_script {language: "python"}` (Jython) so the researcher sees the result, not the wizard. Check first with `fiji_plugins {query: "TrackMate"}` (also lists `Manual tracking with TrackMate`, `Load a TrackMate file`); `MTrack2` and `Manual Tracking` ship as jars (`MTrack2_-2.0.1.jar`, `Manual_Tracking-2.1.1.jar`) and appear under `Plugins ▷ Tracking` in the GUI session.

What the data needs: a time axis. `image.frames > 1` in `fiji_context`. A stack that was saved with its time points as slices (`slices > 1, frames == 1`) is treated as time by the script below (it swaps Z and T, the usual TrackMate convention). A real 3D+T hyperstack (`slices > 1` and `frames > 1`) is tracked in 3D. Multichannel: pick `<channel>` (the nuclear or cytoplasmic marker that is visible in every frame).

Verified on the installed Fiji (2.18.0, TrackMate 8.1.6) on 17 Oct 2026, headless from the command line (`Fiji.app/fiji --headless --run script.py 'imagePath="…",…'`), on the TrackMate tutorial time-lapse `.local/data/fiji/trackmate/FakeTracks.tif` (128 × 128 px, 50 frames, 8-bit, synthetic spots over noise; downloaded by `script/setup-fiji-samples.py`). The display part (overlay on the hyperstack) only runs in the GUI session; it is guarded by `GraphicsEnvironment.isHeadless()` and was not exercised headless.

## T1. TrackMate: LoG detector + LAP tracker, overlay, counts, CSV/XML export

`fiji_run_script {language: "python", code: …}`. Set the `#@` parameter defaults or edit the values at the top; from the gateway the script parameters are the first lines (SciJava script parameters), so a plain edit of the defaults is enough. `imagePath` empty = the active image.

```python
#@ String (value="") imagePath
#@ Integer (value=1) channel
#@ Double (value=2.5) radius
#@ Double (value=-1.0) quality
#@ Double (value=15.0) linking
#@ Double (value=15.0) gapClosing
#@ Integer (value=2) frameGap
#@ Double (value=0.0) minDisplacement
#@ String (value="") outPrefix
import sys
from java.awt import GraphicsEnvironment
from java.io import File
from ij import IJ, WindowManager
from fiji.plugin.trackmate import Model, Settings, TrackMate, SelectionModel, Logger
from fiji.plugin.trackmate.detection import LogDetectorFactory
from fiji.plugin.trackmate.tracking.jaqaman import SparseLAPTrackerFactory
from fiji.plugin.trackmate.features import FeatureFilter
from fiji.plugin.trackmate.io import CSVExporter, TmXmlWriter
from fiji.plugin.trackmate.action import ExportTracksToXML
from fiji.plugin.trackmate.util import TMUtils
import jarray

reload(sys)
sys.setdefaultencoding('utf-8')

imp = IJ.openImage(imagePath) if imagePath else WindowManager.getCurrentImage()
if imp is None:
    raise Exception('No image is open.')
dims = imp.getDimensions()  # XYCZT
if dims[4] == 1 and dims[3] > 1:
    imp.setDimensions(dims[2], dims[4], dims[3])  # slices were time points: swap Z and T
if imp.getNFrames() == 1:
    raise Exception(imp.getTitle() + ' has a single frame; nothing to track.')

model = Model()
model.setLogger(Logger.VOID_LOGGER)
settings = Settings(imp)
settings.detectorFactory = LogDetectorFactory()
settings.detectorSettings = {'DO_SUBPIXEL_LOCALIZATION': True, 'RADIUS': float(radius), 'TARGET_CHANNEL': int(channel),
                             'THRESHOLD': 0.0, 'DO_MEDIAN_FILTERING': False}
settings.trackerFactory = SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['LINKING_MAX_DISTANCE'] = float(linking)
settings.trackerSettings['GAP_CLOSING_MAX_DISTANCE'] = float(gapClosing)
settings.trackerSettings['MAX_FRAME_GAP'] = int(frameGap)
settings.trackerSettings['ALLOW_TRACK_SPLITTING'] = False
settings.trackerSettings['ALLOW_TRACK_MERGING'] = False
settings.addAllAnalyzers()
if minDisplacement > 0:
    settings.addTrackFilter(FeatureFilter('TRACK_DISPLACEMENT', float(minDisplacement), True))

trackmate = TrackMate(model, settings)
if not trackmate.checkInput() or not trackmate.execDetection() or not trackmate.execInitialSpotFiltering():
    raise Exception(trackmate.getErrorMessage())
qualities = sorted([s.getFeature('QUALITY') for s in model.getSpots().iterable(False)])
if not qualities:
    raise Exception('No spots detected; try a different radius or channel.')
if quality < 0:  # auto: Otsu split of the quality histogram, what the TrackMate wizard proposes
    quality = TMUtils.otsuThreshold(jarray.array(qualities, 'd'))
settings.addSpotFilter(FeatureFilter('QUALITY', float(quality), True))
if not (trackmate.computeSpotFeatures(True) and trackmate.execSpotFiltering(True) and trackmate.execTracking()
        and trackmate.computeEdgeFeatures(True) and trackmate.computeTrackFeatures(True) and trackmate.execTrackFiltering(True)):
    raise Exception(trackmate.getErrorMessage())

tm = model.getTrackModel()
fm = model.getFeatureModel()
ids = list(tm.trackIDs(True))
unit = imp.getCalibration().getUnit()
timeUnit = imp.getCalibration().getTimeUnit()
lengths = [tm.trackSpots(tid).size() for tid in ids]
disp = [fm.getTrackFeature(tid, 'TRACK_DISPLACEMENT') or 0.0 for tid in ids]
speed = [fm.getTrackFeature(tid, 'TRACK_MEAN_SPEED') or 0.0 for tid in ids]
mean = lambda xs: sum(xs) / float(len(xs)) if xs else 0.0
print('frames=%d candidateSpots=%d qualityCutoff=%.1f spots=%d tracks=%d meanTrackLength=%.1f frames meanDisplacement=%.1f %s meanSpeed=%.2f %s/%s radius=%s linking=%s gap=%d'
      % (imp.getNFrames(), len(qualities), quality, model.getSpots().getNSpots(True), len(ids), mean(lengths), mean(disp), unit, mean(speed), unit, timeUnit, radius, linking, frameGap))
if outPrefix:
    CSVExporter.exportSpots(outPrefix + '-spots.csv', model, True)
    ExportTracksToXML.export(model, settings, File(outPrefix + '-tracks.xml'))
    writer = TmXmlWriter(File(outPrefix + '.xml'), Logger.VOID_LOGGER)
    writer.appendModel(model); writer.appendSettings(settings); writer.writeToFile()
    print('exported ' + outPrefix + '-spots.csv, -tracks.xml and .xml (open with Plugins > Tracking > Load a TrackMate file)')
if not GraphicsEnvironment.isHeadless():
    from fiji.plugin.trackmate.gui.displaysettings import DisplaySettingsIO
    from fiji.plugin.trackmate.gui.displaysettings.DisplaySettings import TrackMateObject
    from fiji.plugin.trackmate.features.track import TrackIndexAnalyzer
    from fiji.plugin.trackmate.visualization.hyperstack import HyperStackDisplayer
    ds = DisplaySettingsIO.readUserDefault()
    ds.setTrackColorBy(TrackMateObject.TRACKS, TrackIndexAnalyzer.TRACK_INDEX)
    ds.setSpotColorBy(TrackMateObject.TRACKS, TrackIndexAnalyzer.TRACK_INDEX)
    ds.setTrackDisplayMode(ds.TrackDisplayMode.FULL)
    imp.show()
    HyperStackDisplayer(model, SelectionModel(model), imp, ds).render()
```

Parameters, from the context rather than from the researcher:

- `radius`: half the object diameter in calibrated units (`image.calibration.unit`): nuclei at 20× widefield ≈ 5–8 µm, spots/vesicles ≈ 0.3–0.5 µm, FakeTracks' synthetic spots 2.5 px. Read a nucleus size from a quick `fiji_measure` of one object or recipe B1's mean area (`radius ≈ sqrt(meanArea / π)`).
- `quality`: `-1` lets the script split the quality histogram with Otsu, which is what the wizard's initial threshold does; set a number to be stricter (fewer, brighter spots) or looser. The printed `candidateSpots` and `qualityCutoff` say what was cut.
- `linking` / `gapClosing`: the largest distance an object moves between frames (calibrated units); start at 2–3 object radii. `frameGap` bridges missed detections (2 = one missing frame).
- `minDisplacement`: drop tracks that never move further than this (the tutorial uses 10 px to drop immobile spots).
- `outPrefix`: `<root>/.local/exports/<name>` to get the CSV of spots (frame, track id, x, y, z, intensity…), a simplified track XML and a full TrackMate file.

What the researcher sees: the tracks drawn over the hyperstack in track colours (full track display), the spots as circles; `fiji_highlight` with the image bounds points at it. Say the printed numbers: how many objects tracked, mean track length in frames, mean displacement and speed in the image's units.

Verified (FakeTracks, channel 1, radius 2.5, quality 30 as in the TrackMate tutorial, linking 15, gap 15, frameGap 2):

`frames=50 candidateSpots=22883 qualityCutoff=30.0 spots=214 tracks=11 meanTrackLength=19.0 frames meanDisplacement=21.8 pixel meanSpeed=1.61 pixel/sec radius=2.5 linking=15.0 gap=2` and `exported …/faketracks-spots.csv, -tracks.xml and .xml` (41 KB CSV, 17 KB simplified XML, 180 KB TrackMate file). The time unit says `sec` because the sample carries no frame interval; ImageJ's default is 1 s per frame.

Verified with the automatic quality cut-off (`quality=-1`):

`frames=50 candidateSpots=22883 qualityCutoff=27.2 spots=226 tracks=8 meanTrackLength=28.1 frames meanDisplacement=28.0 pixel meanSpeed=2.02 pixel/sec radius=2.5 linking=15.0 gap=2`; adding `minDisplacement` 10 (drop the immobile spots, as the tutorial does) → `spots=226 tracks=4 meanTrackLength=21.0 frames meanDisplacement=55.8 pixel meanSpeed=3.86 pixel/sec`. On the real 5D sample mitosis.tif (channel 2, radius 1.0 µm, auto quality, linking 2 µm, 3D over its 5 slices) → `frames=51 candidateSpots=3491 qualityCutoff=9530.8 spots=283 tracks=33 meanTrackLength=7.8 frames meanDisplacement=1.0 µm meanSpeed=3.35 µm/sec` in 5.8 s (chromosome fragments of one dividing cell, not cells).

Headless run time ≈ 4 s for FakeTracks. Tracks that split or merge (dividing cells) need `ALLOW_TRACK_SPLITTING = True`; then `tracks` counts lineages and the CSV's `TRACK_ID` groups the branches.

## T2. Macro fallback: threshold every frame, link nearest neighbours

When TrackMate is unavailable, or the objects are large blobs rather than spots and the researcher wants "the same segmentation I just saw, over time", a macro does it: per-frame `Convert to Mask` with the chosen method, `Analyze Particles` with `stack` to get every frame's centroids, a nearest-neighbour link to the previous frame within `<maxDistance>`, and polylines of the long tracks as an overlay on the original. `fiji_run_macro`:

```ijm
original = getTitle();
run("Select None");
Stack.getDimensions(width, height, channels, slices, frames);
if (frames == 1 && slices > 1) { run("Properties...", "channels=" + channels + " slices=1 frames=" + slices); frames = slices; }
if (frames == 1) exit(original + " has a single frame; nothing to track.");
run("Duplicate...", "title=track duplicate channels=<channel>");
run("Gaussian Blur...", "sigma=<sigma> stack");
run("Convert to Mask", "method=<method> background=Dark calculate black");
run("Set Measurements...", "area centroid stack display redirect=None decimal=2");
run("Clear Results");
run("Analyze Particles...", "size=<minSize>-Infinity pixel show=Nothing display clear stack");
close("track");
n = nResults;
xs = newArray(n); ys = newArray(n); fs = newArray(n); track = newArray(n);
for (i = 0; i < n; i++) { xs[i] = getResult("X", i); ys[i] = getResult("Y", i); fs[i] = getResult("Slice", i); track[i] = -1; }
maxDistance = <maxDistance>;
nextId = 0;
for (i = 0; i < n; i++) {
  if (fs[i] == 1) { track[i] = nextId; nextId++; continue; }
  best = -1; bestD = maxDistance;
  for (j = 0; j < n; j++) {
    if (fs[j] != fs[i] - 1) continue;
    d = sqrt((xs[i] - xs[j]) * (xs[i] - xs[j]) + (ys[i] - ys[j]) * (ys[i] - ys[j]));
    if (d < bestD) { bestD = d; best = j; }
  }
  if (best >= 0) { taken = false; for (k = 0; k < n; k++) if (k != i && fs[k] == fs[i] && track[k] == track[best]) taken = true; if (!taken) track[i] = track[best]; }
  if (track[i] < 0) { track[i] = nextId; nextId++; }
}
for (i = 0; i < n; i++) setResult("Track", i, track[i]);
updateResults();
selectImage(original);
run("Remove Overlay");
minLength = <minLength>;
longTracks = 0; sumLen = 0;
for (t = 0; t < nextId; t++) {
  m = 0; for (i = 0; i < n; i++) if (track[i] == t) m++;
  if (m < minLength) continue;
  longTracks++; sumLen += m;
  px = newArray(m); py = newArray(m); k = 0;
  for (i = 0; i < n; i++) if (track[i] == t) { px[k] = xs[i]; py[k] = ys[i]; k++; }
  makeSelection("polyline", px, py);
  Overlay.addSelection("yellow", 1);
}
run("Select None");
print("detections=" + n + " over " + frames + " frames; tracks=" + nextId + " of which " + longTracks + " span >= " + minLength + " frames (mean length " + d2s(sumLen / maxOf(longTracks, 1), 1) + ") maxDistance=" + maxDistance + " px; overlay polylines=" + Overlay.size);
```

`X`/`Y` are centroids in calibrated units when the image has a scale (then give `<maxDistance>` in those units too). The Results table keeps one row per detection with `Slice` (= frame) and `Track`, which `fiji_export_results` saves. Nearest-neighbour linking is greedy: it has no gap closing and cannot follow splits or crossings, so for anything beyond a demo use T1 and say so. The loop is O(n²) in detections; fine up to a few thousand.

Verified (FakeTracks, channel 1, sigma 1, Otsu, minSize 4 px, maxDistance 15, minLength 5):

`detections=260 over 50 frames; tracks=82 of which 9 span >= 5 frames (mean length 20.8) maxDistance=15 px; overlay polylines=9` in 2.6 s (TrackMate finds 8–11 tracks on the same data depending on the quality cut; the greedy linker fragments crossings into extra short tracks, hence 82 before the length filter).

## Reporting and follow-ups

- "How fast are they moving?": T1 prints `meanSpeed` in `unit/timeUnit`; with no time calibration the unit is per frame (`Stack.setFrameInterval` / `Image ▷ Properties` sets it).
- "Show me the longest track": the CSV's `TRACK_ID` with the most rows; `fiji_highlight` the bounding box of its spots (x/y columns).
- "Save the tracks": rerun T1 with `outPrefix`, or `Plugins ▷ Tracking ▷ Load a TrackMate file` for the XML in the GUI.
- Overlays stay on the image until `fiji_overlay {clear: true}`; the TrackMate overlay is a separate layer that a new `fiji_run_script` run replaces.

Sources: imagej.net `plugins/trackmate/scripting/scripting.md` and `trackmate-detectors-trackers-keys.md` (local copies under `.local/upstream/imagej-docs`), the upstream example scripts `ExampleScript_ExecTracking.py`, `Run_TrackMate_Headless.groovy`, `ExampleScript_ExportToFiles.py` (fiji/TrackMate `scripts/`), `TMUtils.otsuThreshold` and `TrackMate.exec*` from TrackMate-8.1.6.jar.
