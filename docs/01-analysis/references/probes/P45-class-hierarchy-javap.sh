#!/usr/bin/env bash
# P45 - Superclass/interface declarations of representative IJ1/Fiji/IJ2 classes,
# read from the pinned installed jars with the bundled JDK's javap (read-only).
# Usage: bash P45-class-hierarchy-javap.sh > P45-class-hierarchy-javap.out.txt
FIJI="C:/Users/arnav/Documents/dev/Fiji"
JAVAP="$FIJI/java/win64/zulu21.42.19-ca-jdk21.0.7-win_x64/bin/javap.exe"
CP="$FIJI/jars/*;$FIJI/plugins/*"
CLASSES="
ij.gui.GenericDialog ij.gui.NonBlockingGenericDialog ij.gui.ImageWindow ij.gui.StackWindow ij.gui.ImageCanvas
ij.gui.Toolbar ij.plugin.frame.PlugInFrame ij.plugin.frame.PlugInDialog ij.plugin.frame.RoiManager ij.plugin.frame.Recorder
ij.plugin.frame.ContrastAdjuster ij.plugin.frame.ThresholdAdjuster ij.text.TextWindow ij.measure.ResultsTable
ij.plugin.filter.GaussianBlur ij.plugin.filter.ParticleAnalyzer ij.plugin.filter.Analyzer ij.plugin.Thresholder
ij.plugin.filter.ExtendedPlugInFilter ij.plugin.filter.PlugInFilter ij.plugin.PlugIn
fiji.threshold.Auto_Threshold sc.fiji.skeletonize3D.Skeletonize3D_ sc.fiji.analyzeSkeleton.AnalyzeSkeleton_
sc.fiji.coloc.Coloc_2 sc.fiji.cellCounter.Cell_Counter sc.fiji.cellCounter.CellCounter
trainableSegmentation.Weka_Segmentation trainableSegmentation.Weka_Segmentation\$CustomWindow
ij3d.ImageJ_3D_Viewer ij3d.Image3DUniverse ij3d.ImageWindow3D ij3d.DefaultUniverse
ini.trakem2.New_Project ini.trakem2.ControlWindow ini.trakem2.display.Display
fiji.plugin.trackmate.TrackMateRunner fiji.plugin.trackmate.TrackMatePlugIn fiji.plugin.trackmate.TrackMate
bdv.ij.BigDataViewerPlugIn bdv.viewer.ViewerFrame
loci.plugins.LociImporter loci.plugins.BF loci.plugins.in.ImporterOptions loci.plugins.LociExporter
fiji.plugin.volumeviewer.Volume_Viewer Interactive_3D_Surface_Plot plugin.Stitching_Grid
net.imagej.plugins.commands.imglib.GaussianBlur net.imagej.legacy.command.LegacyCommand
"
for c in $CLASSES; do
  echo "### $c"
  "$JAVAP" -cp "$CP" "$c" 2>&1 | grep -E '^(public|final|abstract|class|interface| *public (abstract )?(final )?(class|interface))|Error|not found' | head -3
done
