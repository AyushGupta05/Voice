// @recipe trackmate-spots
// @title TrackMate spot detection and tracking (LoG + LAP)
// @when detect and track spots or particles with TrackMate: sub-pixel LoG spot detection, linking with gaps, splits and merges ("use TrackMate", "track the spots", "detect spots in every frame", "particle tracking with TrackMate")
// @param channel = 1 | channel with the spots, 1-based
// @param radius = 2.5 | expected spot radius, calibrated units
// @param quality = 0 | LoG quality threshold (0 = automatic: Otsu threshold on the detected spot qualities); raise it to drop faint detections
// @param maxDistance = 10 | largest frame-to-frame move, calibrated units
// @param maxGap = 2 | frames a spot may disappear and still be linked
// @param splitting = false | allow track splitting (divisions)
// @param merging = false | allow track merging
// @output TrackMate's tracks drawn on the image in an interactive session. Reports spots, tracks, mean spots per frame, mean track length (spots) and mean track speed (calibrated units per frame interval) from TrackMate's own features.
// @say TrackMate found {spots} spots in {tracks} tracks, mean speed {meanSpeed} {unit} per {timeUnit}.
channel = parseFloat(pennyParam("channel", "1"));
radius = parseFloat(pennyParam("radius", "2.5"));
quality = parseFloat(pennyParam("quality", "0"));
maxDistance = parseFloat(pennyParam("maxDistance", "10"));
maxGap = parseFloat(pennyParam("maxGap", "2"));
splitting = pennyParam("splitting", "false"); splitting = (splitting == "true");
merging = pennyParam("merging", "false"); merging = (merging == "true");

if (nImages == 0) { print("RESULT error=no_image"); exit; }
getDimensions(w, h, channels, slices, frames);
// TrackMate tracks over frames; a single-channel z/t stack with one frame is read as time.
if (frames == 1 && slices > 1 && channels == 1) run("Properties...", "channels=1 slices=1 frames=" + slices);
getDimensions(w, h, channels, slices, frames);
if (frames < 2) { print("RESULT error=not_a_time_series"); exit; }
getPixelSize(unit, pw, ph);
Stack.getUnits(xu, yu, zu, timeUnit, vu);
if (Stack.getFrameInterval() <= 0) timeUnit = "frame";
gap = "false"; if (maxGap > 0) gap = "true";
splits = "false"; if (splitting) splits = "true";
merges = "false"; if (merging) merges = "true";
js = "var T = Packages.fiji.plugin.trackmate, imp = Packages.ij.WindowManager.getCurrentImage();"
  + "var model = new T.Model(), settings = new T.Settings(imp);"
  + "settings.detectorFactory = new T.detection.LogDetectorFactory(); var d = settings.detectorFactory.getDefaultSettings();"
  + "d.put('RADIUS', new java.lang.Double(" + radius + ")); d.put('THRESHOLD', new java.lang.Double(" + quality + ")); d.put('TARGET_CHANNEL', new java.lang.Integer(" + channel + "));"
  + "d.put('DO_SUBPIXEL_LOCALIZATION', java.lang.Boolean.TRUE); d.put('DO_MEDIAN_FILTERING', java.lang.Boolean.FALSE); settings.detectorSettings = d;"
  + "settings.trackerFactory = new T.tracking.jaqaman.SparseLAPTrackerFactory(); var s = settings.trackerFactory.getDefaultSettings();"
  + "s.put('LINKING_MAX_DISTANCE', new java.lang.Double(" + maxDistance + ")); s.put('GAP_CLOSING_MAX_DISTANCE', new java.lang.Double(" + maxDistance + "));"
  + "s.put('MAX_FRAME_GAP', new java.lang.Integer(" + maxGap + ")); s.put('ALLOW_GAP_CLOSING', java.lang.Boolean.valueOf(" + gap + "));"
  + "s.put('ALLOW_TRACK_SPLITTING', java.lang.Boolean.valueOf(" + splits + ")); s.put('ALLOW_TRACK_MERGING', java.lang.Boolean.valueOf(" + merges + ")); settings.trackerSettings = s;"
  + "settings.addAllAnalyzers(); var tm = new T.TrackMate(model, settings);"
  + "var ok, used = " + quality + ";"
  + "if (used > 0) ok = tm.checkInput() && tm.process(); else {"
  + "ok = tm.checkInput() && tm.execDetection(); if (ok) { var qs = [], it = model.getSpots().iterator(false); while (it.hasNext()) qs.push(it.next().getFeature('QUALITY'));"
  + "used = T.util.TMUtils.otsuThreshold(Java.to(qs, 'double[]')); settings.addSpotFilter(new T.features.FeatureFilter('QUALITY', used, true));"
  + "ok = tm.execInitialSpotFiltering() && tm.computeSpotFeatures(false) && tm.execSpotFiltering(false) && tm.execTracking() && tm.computeEdgeFeatures(false) && tm.computeTrackFeatures(false) && tm.execTrackFiltering(false); } }"
  + "if (!ok) { 'ERROR ' + tm.getErrorMessage(); } else {"
  + "var fm = model.getFeatureModel(), ids = model.getTrackModel().trackIDs(true).toArray(), speed = 0, length = 0;"
  + "for (var i = 0; i < ids.length; i++) { speed += fm.getTrackFeature(ids[i], 'TRACK_MEAN_SPEED'); length += fm.getTrackFeature(ids[i], 'NUMBER_SPOTS'); }"
  + "if (imp.getWindow() != null) { var V = T.visualization.hyperstack.HyperStackDisplayer; var view = new V(model, new T.SelectionModel(model), imp, T.gui.displaysettings.DisplaySettingsIO.readUserDefault()); view.render(); view.refresh(); }"
  + "model.getSpots().getNSpots(true) + ' ' + ids.length + ' ' + (ids.length ? speed / ids.length : 0) + ' ' + (ids.length ? length / ids.length : 0) + ' ' + used; }";
answer = eval("js", js);
if (startsWith(answer, "ERROR")) { print("RESULT error=trackmate_failed message=[" + answer + "]"); exit; }
parts = split(answer, " ");
spots = parseInt(parts[0]); tracks = parseInt(parts[1]);
print("RESULT spots=" + spots + " tracks=" + tracks + " spotsPerFrame=" + d2s(spots / frames, 2) + " meanTrackSpots=" + d2s(parseFloat(parts[3]), 2) + " meanSpeed=" + d2s(parseFloat(parts[2]), 3) + " quality=" + d2s(parseFloat(parts[4]), 2) + " unit=" + replace(unit, " ", "_") + " timeUnit=" + replace(timeUnit, " ", "_") + " frames=" + frames + " channel=" + channel);

function pennyParam(key, fallback) { a = " " + getArgument() + " "; i = indexOf(a, " " + key + "="); if (i < 0) return fallback; v = substring(a, i + lengthOf(key) + 2); if (startsWith(v, "[")) return substring(v, 1, indexOf(v, "]")); return substring(v, 0, indexOf(v, " ")); }
