"""Curated MVP core set: macro parameter keys (derived from GenericDialog calls at
imagej/ImageJ@v1.54p) and per-command attributes. Imported by build_outputs.py.

Key derivation rule: GenericDialog stores Macro.trimKey(label) (GenericDialog.java#L234;
Macro.java#L159-L168): first word of the label, cut at ':' and lower-cased. Numbers and
strings are read with Macro.getValue (GenericDialog.java#L1197, #L1316), choices with
Macro.getValue (#L1391), and a checkbox is true iff "key " is present (#L1349-L1350);
an absent checkbox key means false, NOT the dialog default, in macro mode.
"""
R = 'imagej/ImageJ@v1.54p:'
B = 'boolean (present=true; absent=false in macro mode)'
INTERP = 'None|Bilinear|Bicubic (ImageProcessor.java#L1012-L1016)'
STACKKEY = ('stack', B, 'absent (current slice only)', 'stacks only; answers the "Process all N images?" dialog by key presence', 'stack')

# command_id -> (creates_new_image, preconditions)
CMD = {
 'ij1:Edit>Invert': ('no', 'any type except 8-bit indexed colour (Filters.java#L26 DOES_ALL-DOES_8C); ROI masks the operation'),
 'ij1:Process>Filters>Gaussian Blur...': ('no', 'any type (GaussianBlur.java#L47 DOES_ALL)'),
 'ij1:Process>Filters>Median...': ('no', 'any type (RankFilters.java#L45 DOES_ALL)'),
 'ij1:Process>Filters>Mean...': ('no', 'any type (RankFilters.java#L45 DOES_ALL)'),
 'ij1:Process>Filters>Unsharp Mask...': ('no', 'any type; converted to float internally (UnsharpMask.java#L22 CONVERT_TO_FLOAT)'),
 'ij1:Process>Smooth': ('no', 'any type except 8-bit indexed colour (Filters.java#L26)'),
 'ij1:Process>Subtract Background...': ('conditional', 'any type (BackgroundSubtracter.java#L70 DOES_ALL); new image only with "create" (background) option'),
 'ij1:Process>Enhance Contrast...': ('no', 'any type; modifies pixels only with normalize/equalize, otherwise display range only (ContrastEnhancer.java#L58-L69)'),
 'ij1:Process>Binary>Convert to Mask': ('conditional', 'any type; if no threshold set it auto-thresholds (Thresholder.java#L163, #L218-L219); stacks show "Convert Stack to Binary" dialog (Thresholder.java#L73-L80); new stack only with "create" on stacks'),
 'ij1:Process>Binary>Make Binary': ('conditional', 'as Convert to Mask (Thresholder.java#L40-L51)'),
 'ij1:Process>Binary>Erode': ('no', '8-bit binary image (Binary.java#L33 DOES_8G|DOES_8C; #L42-L45)'),
 'ij1:Process>Binary>Dilate': ('no', '8-bit binary image (Binary.java#L33)'),
 'ij1:Process>Binary>Fill Holes': ('no', '8-bit binary image (Binary.java#L33)'),
 'ij1:Process>Binary>Watershed': ('no', '8-bit binary image (EDM.java; INFERRED from EDM.java#L137 binary background test)'),
 'ij1:Process>Binary>Options...': ('no', 'none; sets global state (Binary.java#L59-L97)'),
 'ij1:Image>Adjust>Auto Threshold': ('conditional', '8-bit or 16-bit only (Auto_Threshold constant pool: "Only 8 and 16-bit images are currently supported."); new images for "Try all"'),
 'ij1:Process>Math>Add...': ('no', 'any type (ImageMath.java#L12 DOES_ALL)'),
 'ij1:Process>Find Maxima...': ('conditional', 'any type (MaximumFinder.java#L77 NO_CHANGES); new image for Single Points/Maxima Within Tolerance/Segmented Particles; Count writes a new ResultsTable when no Results window exists (MaximumFinder.java#L796-L800)'),
 'ij1:Process>Image Calculator...': ('conditional', 'two open images; image2 single or same slice count (ImageCalculator.java#L199); new image with "create"'),
 'ij1:Analyze>Measure': ('no', 'any type (Analyzer.java#L107 DOES_ALL+NO_CHANGES); writes system Results table; reads Analyzer measurement flags'),
 'ij1:Analyze>Set Measurements...': ('no', 'none; sets global Analyzer flags (Analyzer.java#L170-L209)'),
 'ij1:Analyze>Analyze Particles...': ('conditional', 'thresholded image or 8-bit binary (ParticleAnalyzer.java#L819-L829); new image only for Show=Masks/Outlines/etc.; "add" needs ROI Manager (HeadlessException headless, probe P13/P14)'),
 'ij1:Analyze>Histogram': ('yes', 'any type; dialog only for 16/32-bit or stacks (Histogram.java#L108-L123); creates HistogramWindow (HeadlessException headless, probe P13)'),
 'ij1:Analyze>Set Scale...': ('no', 'any type (ScaleDialog.java#L21); changes calibration (global if "global")'),
 'ij1:Analyze>Summarize': ('no', 'a Results table'),
 'ij1:Analyze>Clear Results': ('no', 'none'),
 'ij1:Image>Duplicate...': ('yes', 'an image; selection outside image is an error (Duplicator.java#L575)'),
 'ij1:Image>Crop': ('no', 'area selection required (Resizer.java#L35-L36); modifies the image in place'),
 'ij1:Image>Scale...': ('conditional', 'an image; new window only with "create" (Scaler.java#L320)'),
 'ij1:Image>Adjust>Size...': ('no', 'an image; resizes in place (Resizer.java#L86-L100)'),
 'ij1:Image>Transform>Rotate...': ('no', 'any type (Rotator.java#L12 DOES_ALL); label is "Rotate... " with trailing space (IJ_Props.txt transform06)'),
 'ij1:Image>Stacks>Z Project...': ('yes', 'stack required (ZProjector.java#L145)'),
 'ij1:Image>Stacks>Tools>Make Substack...': ('conditional', 'stack; new image unless "do_not" (SubstackMaker.java#L47, #L64)'),
 'ij1:Image>Stacks>Add Slice': ('no', 'an image; hyperstacks show channel/slice/frame choice (StackEditor.java#L80-L88)'),
 'ij1:Image>Stacks>Delete Slice': ('no', 'stack required (StackEditor.java#L53)'),
 'ij1:Image>Stacks>Make Montage...': ('yes', 'stack (MontageMaker.java#L97-L108)'),
 'ij1:Image>Color>Split Channels': ('yes', 'RGB or multichannel image (ChannelSplitter.java#L31); source closed'),
 'ij1:Image>Color>Merge Channels...': ('yes', 'at least two open single-channel images of equal size (RGBStackMerge.java#L64-L74, #L451)'),
 'ij1:Image>Lookup Tables>Apply LUT': ('no', '8-bit, 16-bit or RGB (LutApplier.java#L19); display range must differ from full range (LutApplier.java#L37)'),
 'ij1:Image>Adjust>Brightness/Contrast...': ('no', 'interactive frame (ContrastAdjuster extends PlugInDialog); HeadlessException headless (probe P13/P14)'),
 'ij1:Image>Adjust>Threshold...': ('no', 'interactive frame (ThresholdAdjuster extends PlugInDialog); HeadlessException headless (probe P13/P14)'),
 'ij1:Edit>Fill': ('no', 'area or line selection required (Filler.java#L30 ROI_REQUIRED, #L49-L51)'),
 'ij1:Edit>Clear': ('no', 'selection required (Filler.java#L30-L41)'),
 'ij1:Edit>Clear Outside': ('no', 'area selection required (Filler.java#L30)'),
 'ij1:Edit>Selection>Add to Manager': ('no', 'a selection; needs ROI Manager window (HeadlessException headless, probe P13/P14)'),
 'ij1:Edit>Undo': ('no', 'single-level, current image only (Undo.java#L121-L132)'),
 'ij1:File>Open...': ('yes', 'a readable file path; intercepted by imagej-legacy DefaultLegacyHooks.interceptRunPlugIn for ij.plugin.Commands("open") (DefaultLegacyHooks.java#L147-L157)'),
 'ij1:File>New>Image...': ('yes', 'none (NewImage.java#L408-L413)'),
 'ij1:File>Save As>Tiff...': ('no', 'an image; writes a file'),
 'ij1:File>Save As>PNG...': ('no', 'an image; writes a file'),
 'ij1:Image>Type>8-bit': ('no', 'converts in place; scaling governed by ImageConverter.doScaling (ImageConverter.java#L15, #L36)'),
 'ij1:Image>Type>16-bit': ('no', 'converts in place (ImageConverter.java#L74)'),
 'ij1:Image>Type>32-bit': ('no', 'converts in place'),
 'ij1:Image>Type>RGB Color': ('no', 'converts in place; composites merge to RGB (Converter.java#L17-L19)'),
}

P = []
def p(cid, key, typ, default, rng, rec, src, cons, unit, glob):
    P.append(dict(command_id=cid, parameter_key=key, type=typ, default=default, range_or_choices=rng,
                  recorded_as=rec, source=R + src, consequential=cons, native_unit=unit,
                  hidden_global_dependency=glob))

GB = 'ij1:Process>Filters>Gaussian Blur...'
p(GB, 'sigma', 'number', '2.0 (static sigmaS, last used)', '>0', 'sigma=2', 'ij/plugin/filter/GaussianBlur.java#L88', 'yes', 'pixels unless "scaled" present (GaussianBlur.java#L133-L134)', 'static sigmaS persists between calls (GaussianBlur.java#L40)')
p(GB, 'scaled', B, 'false', 'shown only if image has a spatial scale (GaussianBlur.java#L89-L91)', 'scaled', 'ij/plugin/filter/GaussianBlur.java#L91', 'yes', 'n/a', 'image Calibration pixelWidth/pixelHeight')
p(GB, *STACKKEY, 'ij/IJ.java#L1255-L1266', 'yes', 'n/a', 'none')
SB = 'ij1:Process>Subtract Background...'
p(SB, 'rolling', 'number', '50', '>0', 'rolling=50', 'ij/plugin/filter/BackgroundSubtracter.java#L96', 'yes', 'pixels', 'static staticRadius (#L49)')
p(SB, 'light', B, 'dialog: Prefs "bs.background" (default true)', '', 'light', 'ij/plugin/filter/BackgroundSubtracter.java#L97', 'yes', 'n/a', 'Prefs bs.background (#L50, #L119)')
p(SB, 'separate', B, 'false', 'RGB only', 'separate', 'ij/plugin/filter/BackgroundSubtracter.java#L98', 'yes', 'n/a', 'none')
p(SB, 'create', B, 'false', '', 'create', 'ij/plugin/filter/BackgroundSubtracter.java#L99', 'yes', 'n/a', 'none')
p(SB, 'sliding', B, 'false', '', 'sliding', 'ij/plugin/filter/BackgroundSubtracter.java#L100', 'yes', 'n/a', 'none')
p(SB, 'disable', B, 'false (smoothing on)', '', 'disable', 'ij/plugin/filter/BackgroundSubtracter.java#L101', 'yes', 'n/a', 'none')
p(SB, *STACKKEY, 'ij/IJ.java#L1255-L1266', 'yes', 'n/a', 'none')
for lab, cid in (('Median', 'ij1:Process>Filters>Median...'), ('Mean', 'ij1:Process>Filters>Mean...')):
    p(cid, 'radius', 'number', '2 (last radius per filter type)', '>0', 'radius=2', 'ij/plugin/filter/RankFilters.java#L122-L124', 'yes', 'pixels', 'static lastRadius[filterType] (RankFilters.java#L122, #L149)')
    p(cid, *STACKKEY, 'ij/IJ.java#L1255-L1266', 'yes', 'n/a', 'none')
UM = 'ij1:Process>Filters>Unsharp Mask...'
p(UM, 'radius', 'number', '1.0', '>0', 'radius=1', 'ij/plugin/filter/UnsharpMask.java#L73', 'yes', 'pixels', 'static sigma (#L20)')
p(UM, 'mask', 'number', '0.6', '0.1-0.9', 'mask=0.60', 'ij/plugin/filter/UnsharpMask.java#L74', 'yes', 'dimensionless', 'static weight (#L21)')
EC = 'ij1:Process>Enhance Contrast...'
p(EC, 'saturated', 'number', '0.35', '0-100 (clamped, ContrastEnhancer.java#L89-L90)', 'saturated=0.35', 'ij/plugin/ContrastEnhancer.java#L58', 'yes', 'percent', 'static gSaturated (#L50)')
p(EC, 'normalize', B, 'false', 'not shown for RGB or composite (#L59-L60)', 'normalize', 'ij/plugin/ContrastEnhancer.java#L60', 'yes', 'n/a', 'none')
p(EC, 'equalize', B, 'false', '', 'equalize', 'ij/plugin/ContrastEnhancer.java#L65', 'yes', 'n/a', 'none')
p(EC, 'process_all', B, 'false', 'stacks only', 'process_all', 'ij/plugin/ContrastEnhancer.java#L68', 'yes', 'n/a', 'none')
p(EC, 'use', B, 'false', 'stacks only ("Use stack histogram")', 'use', 'ij/plugin/ContrastEnhancer.java#L69', 'yes', 'n/a', 'none')
for cid in ('ij1:Process>Binary>Convert to Mask', 'ij1:Process>Binary>Make Binary'):
    p(cid, '(none for single images)', '', '', '', 'run("Convert to Mask")', 'ij/plugin/Thresholder.java#L40-L51', 'yes', 'n/a', 'Prefs.blackBackground (Thresholder.java#L246); current threshold or auto-threshold (#L163, #L218-L219)')
    p(cid, 'method', 'choice', 'Default (stacks)', 'Default|Huang|Intermodes|IsoData|IJ_IsoData|Li|MaxEntropy|Mean|MinError|Minimum|Moments|Otsu|Percentile|RenyiEntropy|Shanbhag|Triangle|Yen (AutoThresholder.java#L11-L28)', 'method=Default', 'ij/plugin/Thresholder.java#L74', 'yes', 'n/a', 'static staticMethod (#L35)')
    p(cid, 'background', 'choice', 'Default', 'Default|Dark|Light (Thresholder.java#L18)', 'background=Dark', 'ij/plugin/Thresholder.java#L75', 'yes', 'n/a', 'none')
    p(cid, 'calculate', B, 'false in macro mode (Thresholder.java#L69)', 'stacks', 'calculate', 'ij/plugin/Thresholder.java#L76', 'yes', 'n/a', 'none')
    p(cid, 'only', B, 'false', 'stacks; legacy key "slice" rewritten to "only" (#L66-L68)', 'only', 'ij/plugin/Thresholder.java#L77', 'yes', 'n/a', 'none')
    p(cid, 'black', B, 'Prefs.blackBackground', 'stacks', 'black', 'ij/plugin/Thresholder.java#L78', 'yes', 'n/a', 'writes Prefs.blackBackground (#L100, restored #L139)')
    p(cid, 'list', B, 'false', 'stacks', 'list', 'ij/plugin/Thresholder.java#L79', 'no', 'n/a', 'none')
    p(cid, 'create', B, 'false', 'stacks', 'create', 'ij/plugin/Thresholder.java#L80', 'no', 'n/a', 'none')
AT = 'ij1:Image>Adjust>Auto Threshold'
AS = 'Fiji/plugins/Auto_Threshold-1.18.0.jar!fiji.threshold.Auto_Threshold (javap -v constant pool; source not cached)'
for key, typ, rng, cons in (('method', 'choice', 'Try all|Default|Huang|Huang2|Intermodes|IsoData|Li|MaxEntropy|Mean|MinError(I)|Minimum|Moments|Otsu|Percentile|RenyiEntropy|Shanbhag|Triangle|Yen', 'yes'),
                            ('ignore_black', B, '', 'yes'), ('ignore_white', B, '', 'yes'), ('white', B, '"White objects on black background"', 'yes'),
                            ('setthreshold', B, '"SetThreshold instead of Threshold (single images)"', 'yes'), ('show', B, '"Show threshold values in log window"', 'no'),
                            ('stack', B, '', 'yes'), ('use_stack_histogram', B, '', 'yes')):
    p(AT, key, typ, 'UNKNOWN (source not inspected)', rng, key + ('=Otsu' if key == 'method' else ''), '', cons, 'n/a', 'UNKNOWN')
    P[-1]['source'] = AS + '; key INFERRED from dialog label via Macro.trimKey'
SM = 'ij1:Analyze>Set Measurements...'
for i, (lab, key) in enumerate((('Area', 'area'), ('Mean gray value', 'mean'), ('Standard deviation', 'standard'), ('Modal gray value', 'modal'), ('Min & max gray value', 'min'),
                                 ('Centroid', 'centroid'), ('Center of mass', 'center'), ('Perimeter', 'perimeter'), ('Bounding rectangle', 'bounding'), ('Fit ellipse', 'fit'),
                                 ('Shape descriptors', 'shape'), ("Feret's diameter", "feret's"), ('Integrated density', 'integrated'), ('Median', 'median'), ('Skewness', 'skewness'),
                                 ('Kurtosis', 'kurtosis'), ('Area_fraction', 'area_fraction'), ('Stack position', 'stack'))):
    p(SM, key, B, 'current Analyzer flags (Prefs MEASUREMENTS, default AREA+MEAN+MIN_MAX, Analyzer.java#L40)', 'label "%s"' % lab, key, 'ij/plugin/filter/Analyzer.java#L%d' % (175 + i), 'yes', 'calibrated units where applicable', 'writes static Analyzer.systemMeasurements (global, persisted to Prefs)')
for i, (lab, key, cons) in enumerate((('Limit to threshold', 'limit', 'yes'), ('Display label', 'display', 'no'), ('Invert Y coordinates', 'invert', 'yes'),
                                       ('Scientific notation', 'scientific', 'no'), ('Add to overlay', 'add', 'no'), ('NaN empty cells', 'nan', 'no'))):
    p(SM, key, B, 'current Analyzer flags', 'label "%s"' % lab, key, 'ij/plugin/filter/Analyzer.java#L%d' % (197 + i), cons, 'n/a', 'writes static Analyzer.systemMeasurements')
p(SM, 'redirect', 'choice', 'None', 'None|<open image titles>', 'redirect=None', 'ij/plugin/filter/Analyzer.java#L206', 'yes', 'n/a', 'static redirectTarget; legacy keys circularity->shape, slice->stack rewritten (#L167-L170)')
p(SM, 'decimal', 'number', '3 (Prefs PRECISION)', '0-9', 'decimal=3', 'ij/plugin/filter/Analyzer.java#L208', 'no', 'digits', 'Analyzer.precision (#L42)')
p('ij1:Analyze>Measure', '(none)', '', '', '', 'run("Measure")', 'ij/plugin/filter/Analyzer.java#L107', 'yes', 'calibrated units', 'Analyzer measurement flags; redirect image; image Calibration; current ROI')
AP = 'ij1:Analyze>Analyze Particles...'
p(AP, 'size', 'string range', '0-Infinity (reset in macro mode, ParticleAnalyzer.java#L305)', 'min-max', 'size=0-Infinity', 'ij/plugin/filter/ParticleAnalyzer.java#L342', 'yes', 'calibrated area units^2 unless "pixel" (#L293, #L345)', 'image Calibration')
p(AP, 'pixel', B, 'false', 'shown only for scaled images', 'pixel', 'ij/plugin/filter/ParticleAnalyzer.java#L345', 'yes', 'n/a', 'none')
p(AP, 'circularity', 'string range', '0.00-1.00', '0-1', 'circularity=0.00-1.00', 'ij/plugin/filter/ParticleAnalyzer.java#L347', 'yes', 'dimensionless', 'none')
p(AP, 'show', 'choice', 'Nothing', 'Nothing|Overlay|Overlay Masks|Outlines|Bare Outlines|Ellipses|Masks|Count Masks (#L100)', 'show=Nothing', 'ij/plugin/filter/ParticleAnalyzer.java#L348', 'no', 'n/a', 'none')
for i, (key, cons) in enumerate((('display', 'no'), ('exclude', 'yes'), ('clear', 'no'), ('include', 'yes'), ('summarize', 'no'), ('overlay', 'no'), ('add', 'no'), ('composite', 'no'), ('record', 'no'))):
    p(AP, key, B, 'false in macro mode', '', key, 'ij/plugin/filter/ParticleAnalyzer.java#L%d' % (351 + i), cons, 'n/a', 'Prefs OPTIONS (#L98)' if i == 0 else ('ROI Manager instance' if key == 'add' else 'none'))
p(AP, 'in_situ', B, 'false', 'substring test on options (#L299-L300)', 'in_situ', 'ij/plugin/filter/ParticleAnalyzer.java#L299', 'no', 'n/a', 'Prefs.blackBackground (#L527)')
p(AP, '(implicit)', '', '', '', '', 'ij/plugin/filter/ParticleAnalyzer.java#L564', 'yes', 'n/a', 'Analyzer measurement flags select the measured columns; Analyzer redirect image (#L504-L505)')
p(AP, *STACKKEY, 'ij/IJ.java#L1255-L1266', 'yes', 'n/a', 'none')
HI = 'ij1:Analyze>Histogram'
p(HI, 'bins', 'number', '256', '>=2', 'bins=256', 'ij/plugin/Histogram.java#L108', 'no', 'count', 'dialog only for 16/32-bit or stacks')
p(HI, 'use', B, 'true (dialog)', '"Use pixel value range"', 'use', 'ij/plugin/Histogram.java#L109', 'no', 'n/a', 'static staticUseImageMinAndMax (#L17)')
p(HI, 'x_min', 'number', 'image min', '', 'x_min=0', 'ij/plugin/Histogram.java#L118', 'no', 'pixel value', 'none')
p(HI, 'x_max', 'number', 'image max', '', 'x_max=256', 'ij/plugin/Histogram.java#L119', 'no', 'pixel value', 'none')
p(HI, 'y_max', 'string', 'Auto', 'Auto|number', 'y_max=Auto', 'ij/plugin/Histogram.java#L121', 'no', 'count', 'none')
p(HI, 'stack', B, 'false', 'stacks', 'stack', 'ij/plugin/Histogram.java#L123', 'no', 'n/a', 'none')
DU = 'ij1:Image>Duplicate...'
p(DU, 'title', 'string', '<title>-1', '', 'title=dup', 'ij/plugin/Duplicator.java#L484', 'no', 'n/a', 'none')
p(DU, 'ignore', B, 'false', 'only when a selection exists', 'ignore', 'ij/plugin/Duplicator.java#L486', 'yes', 'n/a', 'current ROI')
p(DU, 'duplicate', B, 'false in macro mode (#L478)', 'stacks', 'duplicate', 'ij/plugin/Duplicator.java#L488', 'yes', 'n/a', 'none')
p(DU, 'range', 'string', '1-N', 'stacks', 'range=1-3', 'ij/plugin/Duplicator.java#L490', 'yes', 'slices', 'none')
p(DU, 'channels', 'string', '1-C', 'hyperstacks', 'channels=1-2', 'ij/plugin/Duplicator.java#L603', 'yes', 'index', 'none')
p(DU, 'slices', 'string', '1-Z', 'hyperstacks', 'slices=1-5', 'ij/plugin/Duplicator.java#L608', 'yes', 'index', 'none')
p(DU, 'frames', 'string', '1-T', 'hyperstacks', 'frames=1-10', 'ij/plugin/Duplicator.java#L613', 'yes', 'index', 'none')
p('ij1:Image>Crop', '(none)', '', '', '', 'run("Crop")', 'ij/plugin/Resizer.java#L26-L36', 'yes', 'n/a', 'current ROI')
SC = 'ij1:Image>Scale...'
p(SC, 'x', 'string', '0.5 (static xstr)', 'number or "-"', 'x=0.5', 'ij/plugin/Scaler.java#L272', 'yes', 'factor', 'static xstr (#L15)')
p(SC, 'y', 'string', '0.5', 'number or "-"', 'y=0.5', 'ij/plugin/Scaler.java#L273', 'yes', 'factor', 'static')
p(SC, 'z', 'string', '1.0', 'stacks only', 'z=1.0', 'ij/plugin/Scaler.java#L275', 'yes', 'factor', 'static')
p(SC, 'width', 'string', 'derived', '', 'width=128', 'ij/plugin/Scaler.java#L277', 'yes', 'pixels', 'none')
p(SC, 'height', 'string', 'derived', '', 'height=128', 'ij/plugin/Scaler.java#L278', 'yes', 'pixels', 'none')
p(SC, 'depth', 'string', 'derived', 'stacks', 'depth=3', 'ij/plugin/Scaler.java#L292', 'yes', 'slices', 'none')
p(SC, 'interpolation', 'choice', 'Bilinear', INTERP, 'interpolation=Bilinear', 'ij/plugin/Scaler.java#L313', 'yes', 'n/a', 'static staticInterpolationMethod (Scaler.java#L23)')
p(SC, 'fill', B, 'false', 'conditional', 'fill', 'ij/plugin/Scaler.java#L315', 'yes', 'n/a', 'background colour (Toolbar)')
p(SC, 'average', B, 'true (static)', '', 'average', 'ij/plugin/Scaler.java#L316', 'yes', 'n/a', 'static averageWhenDownsizing (#L21)')
p(SC, 'process', B, 'false', 'stacks', 'process', 'ij/plugin/Scaler.java#L319', 'yes', 'n/a', 'none')
p(SC, 'create', B, 'true (static newWindow)', '', 'create', 'ij/plugin/Scaler.java#L320', 'no', 'n/a', 'static newWindow (#L22)')
p(SC, 'title', 'string', 'unique name from title', '', 'title=scaled', 'ij/plugin/Scaler.java#L323', 'no', 'n/a', 'none')
SZ = 'ij1:Image>Adjust>Size...'
p(SZ, 'width', 'number', 'current', '', 'width=128', 'ij/plugin/Resizer.java#L86', 'yes', 'pixels', 'none')
p(SZ, 'height', 'number', 'current', '', 'height=128', 'ij/plugin/Resizer.java#L87', 'yes', 'pixels', 'none')
p(SZ, 'depth', 'number', 'current', 'stacks', 'depth=3', 'ij/plugin/Resizer.java#L93-L95', 'yes', 'slices', 'none')
p(SZ, 'time', 'number', 'current', 'hyperstacks with frames', 'time=10', 'ij/plugin/Resizer.java#L97', 'yes', 'frames', 'none')
p(SZ, 'constrain', B, 'static', '', 'constrain', 'ij/plugin/Resizer.java#L98', 'yes', 'n/a', 'static')
p(SZ, 'average', B, 'static', '', 'average', 'ij/plugin/Resizer.java#L99', 'yes', 'n/a', 'static')
p(SZ, 'interpolation', 'choice', 'static', INTERP, 'interpolation=Bilinear', 'ij/plugin/Resizer.java#L100', 'yes', 'n/a', 'static')
RO = 'ij1:Image>Transform>Rotate...'
p(RO, 'angle', 'number', '15.0 (static)', 'slider -90..90; any number accepted', 'angle=15', 'ij/plugin/filter/Rotator.java#L149', 'yes', 'degrees', 'static angle (Rotator.java#L13)')
p(RO, 'grid', 'number', '1', '>=0', 'grid=1', 'ij/plugin/filter/Rotator.java#L150', 'no', 'lines', 'static gridLines (#L16)')
p(RO, 'interpolation', 'choice', 'Bilinear', INTERP, 'interpolation=Bilinear', 'ij/plugin/filter/Rotator.java#L151', 'yes', 'n/a', 'static interpolationMethod (#L24)')
p(RO, 'fill', B, 'false', '', 'fill', 'ij/plugin/filter/Rotator.java#L152', 'yes', 'n/a', 'background colour (Toolbar)')
p(RO, 'enlarge', B, 'false', 'conditional (#L153-L154)', 'enlarge', 'ij/plugin/filter/Rotator.java#L154', 'yes', 'n/a', 'none')
p(RO, *STACKKEY, 'ij/IJ.java#L1255-L1266', 'yes', 'n/a', 'none')
ZP = 'ij1:Image>Stacks>Z Project...'
p(ZP, 'start', 'number', '1', '1..N', 'start=1', 'ij/plugin/ZProjector.java#L295', 'yes', 'slice', 'none')
p(ZP, 'stop', 'number', 'N', '1..N', 'stop=3', 'ij/plugin/ZProjector.java#L296', 'yes', 'slice', 'none')
p(ZP, 'projection', 'choice', 'Prefs zproject.method (default Average Intensity)', '[Average Intensity]|[Max Intensity]|[Min Intensity]|[Sum Slices]|[Standard Deviation]|Median (ZProjector.java#L24-L25)', 'projection=[Max Intensity]', 'ij/plugin/ZProjector.java#L297', 'yes', 'n/a', 'Prefs zproject.method (#L27, #L174)')
p(ZP, 'all', B, 'false', 'hyperstacks with frames', 'all', 'ij/plugin/ZProjector.java#L299', 'yes', 'n/a', 'none')
SS = 'ij1:Image>Stacks>Tools>Make Substack...'
p(SS, 'slices', 'string', '""', 'range a-b, a-b-step, or list a,b,c', 'slices=1-2', 'ij/plugin/SubstackMaker.java#L156', 'yes', 'slice index', 'none')
p(SS, 'delete', B, 'false', '', 'delete', 'ij/plugin/SubstackMaker.java#L157', 'yes', 'n/a', 'none')
p(SS, 'do_not', B, 'false', '', 'do_not', 'ij/plugin/SubstackMaker.java#L158', 'no', 'n/a', 'none')
p('ij1:Image>Stacks>Add Slice', 'add', 'choice', 'slice', 'channel|slice|frame (hyperstacks only)', 'add=slice', 'ij/plugin/StackEditor.java#L87', 'no', 'n/a', 'none')
p('ij1:Image>Stacks>Add Slice', 'prepend', B, 'false', 'hyperstacks only', 'prepend', 'ij/plugin/StackEditor.java#L88', 'no', 'n/a', 'none')
p('ij1:Image>Stacks>Delete Slice', 'delete', 'choice', 'slice', 'channel|slice|frame (hyperstacks only)', 'delete=slice', 'ij/plugin/StackEditor.java#L190', 'yes', 'n/a', 'none')
MM = 'ij1:Image>Stacks>Make Montage...'
for ln, key, typ, cons, unit in ((97, 'columns', 'number', 'no', 'count'), (98, 'rows', 'number', 'no', 'count'), (99, 'scale', 'number', 'yes', 'factor'), (101, 'first', 'number', 'no', 'slice'),
                                  (102, 'last', 'number', 'no', 'slice'), (104, 'increment', 'number', 'no', 'slices'), (105, 'border', 'number', 'no', 'pixels'), (106, 'font', 'number', 'no', 'points'),
                                  (107, 'label', B, 'no', 'n/a'), (108, 'use', B, 'no', 'n/a')):
    p(MM, key, typ, 'derived/static', '', key, 'ij/plugin/MontageMaker.java#L%d' % ln, cons, unit, 'static fields (MontageMaker.java#L13-L20)' + ('; foreground colour' if key == 'use' else ''))
p('ij1:Image>Color>Split Channels', '(none)', '', '', '', 'run("Split Channels")', 'ij/plugin/ChannelSplitter.java#L31', 'no', 'n/a', 'none')
MC = 'ij1:Image>Color>Merge Channels...'
for i, key in enumerate(('c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7')):
    p(MC, key, 'choice', '*None* in macro mode', 'open image titles; legacy red/green/blue/gray= rewritten (RGBStackMerge.java#L56-L59)', key + '=r', 'ij/plugin/RGBStackMerge.java#L%d' % (64 + i), 'yes', 'n/a', 'WindowManager image list')
p(MC, 'create', B, 'false in macro mode (#L55)', '', 'create', 'ij/plugin/RGBStackMerge.java#L72', 'yes', 'n/a', 'none')
p(MC, 'keep', B, 'false in macro mode', '', 'keep', 'ij/plugin/RGBStackMerge.java#L73', 'no', 'n/a', 'none')
p(MC, 'ignore', B, 'false in macro mode', '', 'ignore', 'ij/plugin/RGBStackMerge.java#L74', 'yes', 'n/a', 'source LUTs')
p('ij1:Image>Lookup Tables>Apply LUT', 'stack', B, 'absent', 'stacks', 'stack', 'ij/plugin/filter/LutApplier.java#L76-L81', 'yes', 'n/a', 'current display range (min/max) of the image')
p('ij1:Image>Adjust>Brightness/Contrast...', '(interactive frame; no macro keys)', '', '', '', 'Recorder emits setMinAndMax(...) / run("Apply LUT") (UNKNOWN: recorder lines not cited)', 'ij/plugin/frame/ContrastAdjuster.java#L64', 'no', 'n/a', 'display range')
p('ij1:Image>Adjust>Threshold...', '(interactive frame; no macro keys)', '', '', '', 'Recorder emits setThreshold/setAutoThreshold (UNKNOWN: recorder lines not cited)', 'ij/plugin/frame/ThresholdAdjuster.java#L74', 'yes', 'n/a', 'Prefs.blackBackground; ThresholdAdjuster.isDarkBackground (Thresholder.java#L248)')
for cid in ('ij1:Edit>Fill', 'ij1:Edit>Clear', 'ij1:Edit>Clear Outside'):
    p(cid, 'slice', B, 'absent', 'stacks: "slice" = current slice, "stack" = all', 'slice', 'ij/IJ.java#L1255-L1266', 'yes', 'n/a',
      'foreground colour (Toolbar); in macros a prior setColor() value overrides it (Filler.java#L98-L99, probe P14 op 23)' if cid.endswith('Fill') else 'background colour (Toolbar)')
p('ij1:Edit>Selection>Add to Manager', '(none)', '', '', '', 'roiManager("add")', 'ij/plugin/Selection.java#L31', 'no', 'n/a', 'RoiManager singleton')
p('ij1:Edit>Undo', '(none)', '', '', '', 'run("Undo")', 'ij/Undo.java#L121-L132', 'yes', 'n/a', 'static single-level Undo buffer (whatToUndo, impCopy, imageID)')
p('ij1:File>Open...', 'open', 'path', '', 'file path; key = trimKey(dialog title "Open"); "path" also accepted', 'open(path) / run("Open...", "path=[...]")', 'ij/io/OpenDialog.java#L37-L43', 'no', 'n/a', 'imagej-legacy may route to SCIFIO/Bio-Formats (DefaultLegacyHooks.java#L147-L157)')
for cid, t in (('ij1:File>Save As>Tiff...', 'TIFF'), ('ij1:File>Save As>PNG...', 'PNG')):
    p(cid, 'save', 'path', '', 'key = trimKey("Save as %s") = "save"; "path" also accepted (SaveDialog.java#L59-L63)' % t, 'saveAs("%s", path)' % t.title(), 'ij/io/SaveDialog.java#L59-L63', 'no', 'n/a', 'none')
NI = 'ij1:File>New>Image...'
for ln, key, typ, rng in ((408, 'name', 'string', ''), (409, 'type', 'choice', '8-bit|16-bit|32-bit|RGB (NewImage.java#L31)'), (410, 'fill', 'choice', 'White|Black|Ramp|Noise (NewImage.java#L32)'),
                          (411, 'width', 'number', 'pixels'), (412, 'height', 'number', 'pixels'), (413, 'slices', 'number', '')):
    p(NI, key, typ, 'static last-used', rng, key, 'ij/gui/NewImage.java#L%d' % ln, 'no', 'pixels' if key in ('width', 'height') else 'n/a', 'static fields')
for cid in ('ij1:Image>Type>8-bit', 'ij1:Image>Type>16-bit'):
    p(cid, '(none)', '', '', '', 'run("%s")' % cid.split('>')[-1], 'ij/process/ImageConverter.java#L15', 'yes', 'n/a', 'ImageConverter.doScaling set by Edit>Options>Conversions (Options.java#L186)')
p('ij1:Image>Type>32-bit', '(none)', '', '', '', 'run("32-bit")', 'ij/plugin/Converter.java#L76', 'no', 'n/a', 'none')
p('ij1:Image>Type>RGB Color', '(none)', '', '', '', 'run("RGB Color")', 'ij/plugin/Converter.java#L78', 'yes', 'n/a', 'display range/LUT of source')
p('ij1:Edit>Invert', '(none)', '', '', '', 'run("Invert")', 'ij/plugin/filter/Filters.java#L26', 'yes', 'n/a', 'UNKNOWN: 16-bit invert range depends on ShortProcessor.invert (not inspected); Filters.java#L35-L39 resets display range for 16-bit stacks')
p('ij1:Process>Smooth', '(none)', '', '', '', 'run("Smooth")', 'ij/plugin/filter/Filters.java#L26', 'yes', 'n/a', 'none')
for cid in ('ij1:Process>Binary>Erode', 'ij1:Process>Binary>Dilate', 'ij1:Process>Binary>Fill Holes', 'ij1:Process>Binary>Watershed'):
    p(cid, '(none)', '', '', '', 'run("%s")' % cid.split('>')[-1], 'ij/plugin/filter/Binary.java#L119', 'yes', 'n/a', 'Prefs.blackBackground (Binary.java#L119, #L164); Binary iterations/count (Binary.java#L22-L23)')
BO = 'ij1:Process>Binary>Options...'
p(BO, 'iterations', 'number', '1', '1-MAX_ITERATIONS', 'iterations=1', 'ij/plugin/filter/Binary.java#L59', 'yes', 'count', 'static Binary.iterations (#L22)')
p(BO, 'count', 'number', '1 (Prefs)', '1-8', 'count=1', 'ij/plugin/filter/Binary.java#L60', 'yes', 'neighbours', 'Prefs binary.count (#L23, #L75)')
p(BO, 'black', B, 'Prefs.blackBackground', '', 'black', 'ij/plugin/filter/Binary.java#L61', 'yes', 'n/a', 'writes Prefs.blackBackground (#L93-L95)')
p(BO, 'pad', B, 'Prefs.padEdges', '', 'pad', 'ij/plugin/filter/Binary.java#L62', 'yes', 'n/a', 'writes Prefs.padEdges (#L97)')
p(BO, 'edm', 'choice', 'Overwrite', 'Overwrite|8-bit|16-bit|32-bit (Binary.java#L17)', 'edm=Overwrite', 'ij/plugin/filter/Binary.java#L63', 'yes', 'n/a', 'EDM output type (global)')
p(BO, 'do', 'choice', 'Nothing', 'Nothing|Erode|Dilate|Open|Close|Outline|Fill Holes|Skeletonize (Binary.java#L18)', 'do=Nothing', 'ij/plugin/filter/Binary.java#L65', 'yes', 'n/a', 'none')
p('ij1:Process>Math>Add...', 'value', 'number', 'defaultAddValue (static)', '', 'value=20', 'ij/plugin/filter/ImageMath.java#L439', 'yes', 'pixel value', 'none')
p('ij1:Process>Math>Add...', *STACKKEY, 'ij/IJ.java#L1255-L1266', 'yes', 'n/a', 'none')
FM = 'ij1:Process>Find Maxima...'
p(FM, 'prominence', 'number', '10 (static tolerance)', '>=0; legacy key "noise" (MaximumFinder.java#L41, #L75)', 'prominence=10', 'ij/plugin/filter/MaximumFinder.java#L133', 'yes', 'pixel value', 'static tolerance (#L47)')
p(FM, 'strict', B, 'false', '', 'strict', 'ij/plugin/filter/MaximumFinder.java#L134', 'yes', 'n/a', 'none')
p(FM, 'exclude', B, 'false', '', 'exclude', 'ij/plugin/filter/MaximumFinder.java#L135', 'yes', 'n/a', 'none')
p(FM, 'above', B, 'false', 'only if a threshold is set', 'above', 'ij/plugin/filter/MaximumFinder.java#L137', 'yes', 'n/a', 'current threshold')
p(FM, 'light', B, 'false', '', 'light', 'ij/plugin/filter/MaximumFinder.java#L138', 'yes', 'n/a', 'none')
p(FM, 'output', 'choice', 'Single Points', '[Single Points]|[Maxima Within Tolerance]|[Segmented Particles]|[Point Selection]|List|Count (MaximumFinder.java#L67-L68)', 'output=Count', 'ij/plugin/filter/MaximumFinder.java#L139', 'no', 'n/a', 'IJ.isResultsWindow() decides target table for Count (#L797-L799)')
IC = 'ij1:Process>Image Calculator...'
p(IC, 'image1', 'choice', 'current image', 'open image titles', 'image1=a', 'ij/plugin/ImageCalculator.java#L52', 'yes', 'n/a', 'WindowManager image list')
p(IC, 'operation', 'choice', 'Add', 'Add|Subtract|Multiply|Divide|AND|OR|XOR|Min|Max|Average|Difference|Copy|Transparent-zero (ImageCalculator.java#L21)', 'operation=Add', 'ij/plugin/ImageCalculator.java#L53', 'yes', 'n/a', 'none')
p(IC, 'image2', 'choice', 'current image', 'open image titles', 'image2=b', 'ij/plugin/ImageCalculator.java#L58', 'yes', 'n/a', 'WindowManager image list')
p(IC, 'create', B, 'false in macro mode', '', 'create', 'ij/plugin/ImageCalculator.java#L60', 'no', 'n/a', 'none')
p(IC, '32-bit', B, 'false', '', '32-bit', 'ij/plugin/ImageCalculator.java#L61', 'yes', 'n/a', 'none')
p(IC, *STACKKEY, 'ij/IJ.java#L1255-L1266', 'yes', 'n/a', 'none')
SS2 = 'ij1:Analyze>Set Scale...'
p(SS2, 'distance', 'number', 'from line selection or current', '>=0', 'distance=10', 'ij/plugin/filter/ScaleDialog.java#L67', 'yes', 'pixels', 'image Calibration')
p(SS2, 'known', 'number', 'current', '>=0', 'known=1', 'ij/plugin/filter/ScaleDialog.java#L68', 'yes', 'calibrated unit', 'image Calibration')
p(SS2, 'pixel', 'number', '1.0', '>0', 'pixel=1', 'ij/plugin/filter/ScaleDialog.java#L69', 'yes', 'ratio', 'image Calibration')
p(SS2, 'unit', 'string', 'pixel', '', 'unit=um', 'ij/plugin/filter/ScaleDialog.java#L70', 'yes', 'n/a', 'image Calibration')
p(SS2, 'global', B, 'false', '', 'global', 'ij/plugin/filter/ScaleDialog.java#L73', 'yes', 'n/a', 'global calibration applied to all images opened later')
p('ij1:Analyze>Summarize', '(none)', '', '', '', 'run("Summarize")', 'ij/Menus.java#L212', 'no', 'n/a', 'system Results table')
p('ij1:Analyze>Clear Results', '(none)', '', '', '', 'run("Clear Results")', 'ij/Menus.java#L215', 'no', 'n/a', 'system Results table')
