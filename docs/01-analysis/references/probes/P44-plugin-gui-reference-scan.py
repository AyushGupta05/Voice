"""P44 - Static bytecode reference scan of the pinned Fiji plugin jars (read-only).

For every jar under <Fiji>/plugins (recursive) plus selected jars/ entries,
count .class files whose constant pool mentions given JVM internal names.
This is a heuristic: a reference proves the class *links against* the API,
not that the code path executes. It also inventories plugins.config lines and
the SciJava annotation index (META-INF/json/org.scijava.plugin.Plugin).

Usage: python -I P44-plugin-gui-reference-scan.py <FijiDir> > P44-...out.txt
Writes nothing into the Fiji directory.
"""
import sys, os, zipfile, json, collections

fiji = sys.argv[1]
MARKERS = {
    "GenericDialog": b"ij/gui/GenericDialog",
    "NonBlockingGD": b"ij/gui/NonBlockingGenericDialog",
    "ImageWindow": b"ij/gui/ImageWindow",
    "StackWindow": b"ij/gui/StackWindow",
    "ImageCanvas": b"ij/gui/ImageCanvas",
    "Toolbar": b"ij/gui/Toolbar",
    "PlugInFrame": b"ij/plugin/frame/PlugInFrame",
    "RoiManager": b"ij/plugin/frame/RoiManager",
    "WindowManager": b"ij/WindowManager",
    "awt.Frame": b"java/awt/Frame",
    "swing.JFrame": b"javax/swing/JFrame",
    "swing.JDialog": b"javax/swing/JDialog",
    "MouseListener": b"java/awt/event/MouseListener",
    "Java3D-scijava": b"org/scijava/java3d/",
    "Java3D-jogamp": b"org/jogamp/java3d/",
    "Java3D-javax": b"javax/media/j3d/",
    "JOGL": b"com/jogamp/opengl/",
    "SciJavaPlugin": b"org/scijava/plugin/Plugin",
    "ij.PlugIn": b"ij/plugin/PlugIn",
    "PlugInFilter": b"ij/plugin/filter/PlugInFilter",
    "ExtPlugInFilter": b"ij/plugin/filter/ExtendedPlugInFilter",
    "isHeadless": b"isHeadless",
}
EXTRA = ["TrackMate-8.1.6.jar", "bigdataviewer_fiji-6.4.1.jar", "bigdataviewer-core-10.6.11.jar",
         "labkit-ui-0.4.0.jar", "BigStitcher-3.0.8.jar", "imagej-plugins-commands-1.0.0.jar",
         "ij-1.54p.jar", "imagej-legacy-3.0.0.jar", "imagej-ops-2.2.0.jar", "VIB-lib-2.2.0.jar"]
jars = []
for root, _, files in os.walk(os.path.join(fiji, "plugins")):
    for f in files:
        if f.endswith(".jar"):
            jars.append(os.path.join(root, f))
for f in EXTRA:
    jars.append(os.path.join(fiji, "jars", f))

cols = list(MARKERS)
print("# P44 static reference scan; cells = number of .class files referencing the marker")
print("jar\tclasses\tplugins.config\tsj-plugins\t" + "\t".join(cols))
for jp in sorted(jars, key=lambda p: os.path.basename(p).lower()):
    rel = os.path.relpath(jp, fiji).replace("\\", "/")
    try:
        z = zipfile.ZipFile(jp)
    except Exception as e:
        print(rel + "\tERROR " + str(e)); continue
    counts = collections.Counter(); ncls = 0
    for n in z.namelist():
        if not n.endswith(".class"):
            continue
        ncls += 1
        b = z.read(n)
        for k, m in MARKERS.items():
            if m in b:
                counts[k] += 1
    names = set(z.namelist())
    cfg = 0
    if "plugins.config" in names:
        cfg = sum(1 for l in z.read("plugins.config").decode("utf-8", "replace").splitlines()
                  if l.strip() and not l.strip().startswith("#"))
    sj = 0
    if "META-INF/json/org.scijava.plugin.Plugin" in names:
        sj = z.read("META-INF/json/org.scijava.plugin.Plugin").count(b'"class"')
    print(f"{rel}\t{ncls}\t{cfg}\t{sj}\t" + "\t".join(str(counts[k]) for k in cols))
