"""P14 - headless behaviour probe through the `-macro` CLI path.

One throwaway headless Fiji process per operation (the prototype pattern):
  fiji-windows-x64.exe --java-home=<jdk> --allow-multiple --headless -macro <op.ijm> <resultfile>
Each macro writes PASS/FAIL + detail to <resultfile>; a macro error aborts the macro,
so a missing result file (but present 'started' marker) means the operation aborted.

Usage: python -I P14_headless_macro_probe.py <workdir> [batch|nobatch]
"""
import os, subprocess, sys, time

FIJI = r"C:\Users\arnav\Documents\dev\Fiji"
JH = FIJI + r"\java\win64\zulu21.42.19-ca-jdk21.0.7-win_x64"
WD = sys.argv[1]
MODE = sys.argv[2] if len(sys.argv) > 2 else 'batch'
os.makedirs(WD, exist_ok=True)

PREFIX = r'''
var OUT = getArgument();
var TMP = File.getParent(OUT);
File.saveString("started", OUT + ".started");
%s
function blobs() { newImage("blobs","8-bit black",256,256,1); setColor(220); fillOval(10,10,60,60); fillOval(100,40,40,40); fillOval(165,145,70,70); fillOval(35,165,50,50); }
function stack3() { newImage("stack","8-bit ramp",128,128,3); }
function cks() { s=0; for (y=0; y<getHeight(); y++) for (x=0; x<getWidth(); x++) s += (getPixel(x,y)*(x+1)*(y+3)) %% 9973; return s; }
function nbins() { getHistogram(v, c, 256); n=0; for (k=0; k<256; k++) if (c[k]>0) n++; return n; }
function result(ok, detail) { if (ok) File.saveString("PASS\t"+detail, OUT); else File.saveString("FAIL\t"+detail, OUT); }
''' % ('setBatchMode(true);' if MODE == 'batch' else '')

OPS = [
 ('01', 'Invert', 'blobs(); c0=cks(); run("Invert"); result(cks()!=c0, "cks "+c0+"->"+cks());'),
 ('02', 'Gaussian Blur...', 'blobs(); c0=cks(); run("Gaussian Blur...", "sigma=2"); result(cks()!=c0, "cks "+c0+"->"+cks());'),
 ('03', 'Subtract Background...', 'blobs(); run("Add...", "value=20"); getStatistics(a,m0); run("Subtract Background...", "rolling=50"); getStatistics(a,m1); result(m1<m0, "mean "+m0+"->"+m1);'),
 ('04', 'Median...', 'blobs(); setPixel(128,5,255); setPixel(5,128,255); setPixel(250,250,255); c0=cks(); run("Median...", "radius=2"); result(cks()!=c0, "cks "+c0+"->"+cks());'),
 ('05', 'Convert to Mask', 'blobs(); setAutoThreshold("Default dark"); run("Convert to Mask"); result(nbins()==2 && bitDepth()==8, "bins="+nbins()+" inverting="+is("Inverting LUT"));'),
 ('06', 'Auto Threshold', 'blobs(); run("Auto Threshold", "method=Otsu white"); result(nbins()==2, "bins="+nbins());'),
 ('07', 'setAutoThreshold (macro fn)', 'blobs(); setAutoThreshold("Otsu dark"); getThreshold(lo,hi); result(lo!=-1, ""+lo+"-"+hi);'),
 ('08', 'Set Measurements...', 'run("Set Measurements...", "area mean min centroid redirect=None decimal=3"); blobs(); run("Clear Results"); run("Measure"); h=String.getResultsHeadings; result(indexOf(h,"X")>=0 && indexOf(h,"Min")>=0 && indexOf(h,"StdDev")<0, h);'),
 ('09', 'Measure', 'blobs(); run("Clear Results"); run("Measure"); result(nResults==1, "rows="+nResults+" mean="+getResult("Mean",0));'),
 ('10', 'Analyze Particles... (display)', 'blobs(); setThreshold(128,255); run("Analyze Particles...", "size=0-Infinity display clear"); result(nResults==4, "rows="+nResults);'),
 ('11', 'Analyze Particles... (add)', 'blobs(); setThreshold(128,255); roiManager("reset"); run("Analyze Particles...", "size=0-Infinity clear add"); result(roiManager("count")==4, "rm.count="+roiManager("count"));'),
 ('12', 'Histogram', 'blobs(); t=getTitle(); run("Histogram"); result(getTitle()!=t, "current="+getTitle());'),
 ('13', 'Duplicate...', 'blobs(); run("Duplicate...", "title=dup"); result(getTitle()=="dup", "current="+getTitle());'),
 ('14', 'Crop', 'blobs(); makeRectangle(10,10,100,80); run("Crop"); result(getWidth()==100 && getHeight()==80, ""+getWidth()+"x"+getHeight());'),
 ('15', 'Scale...', 'blobs(); run("Scale...", "x=0.5 y=0.5 interpolation=Bilinear average create title=scaled"); result(getTitle()=="scaled" && getWidth()==128, getTitle()+" w="+getWidth());'),
 ('16', 'Rotate... (Image>Transform, trailing space)', 'blobs(); c0=cks(); run("Rotate... ", "angle=15 grid=1 interpolation=Bilinear"); result(cks()!=c0, "cks "+c0+"->"+cks());'),
 ('16b', 'Rotate... (Edit>Selection, no ROI)', 'blobs(); c0=cks(); run("Rotate...", "angle=15"); result(cks()!=c0 || selectionType()!=-1, "cks "+c0+"->"+cks()+" sel="+selectionType());'),
 ('17', 'Z Project...', 'stack3(); run("Z Project...", "projection=[Max Intensity]"); result(startsWith(getTitle(),"MAX_"), getTitle());'),
 ('18', 'Make Substack...', 'stack3(); id0=getImageID(); run("Make Substack...", "slices=1-2"); result(getImageID()!=id0 && nSlices==2, getTitle()+" n="+nSlices);'),
 ('19', 'Split Channels', 'newImage("rgb","RGB ramp",128,128,1); run("Split Channels"); result(endsWith(getTitle(),"(blue)"), "current="+getTitle()+" nImages="+nImages);'),
 ('20', 'Merge Channels...', 'newImage("r","8-bit ramp",64,64,1); newImage("g","8-bit black",64,64,1); run("Merge Channels...", "c1=r c2=g create"); result(is("composite") && nSlices==2, getTitle()+" n="+nSlices);'),
 ('21', 'Enhance Contrast...', 'blobs(); run("Multiply...", "value=0.5"); c0=cks(); run("Enhance Contrast...", "saturated=0.35 normalize"); result(cks()!=c0, "cks "+c0+"->"+cks());'),
 ('22', 'Apply LUT', 'blobs(); c0=cks(); setMinAndMax(50,150); run("Apply LUT"); result(cks()!=c0, "cks "+c0+"->"+cks());'),
 ('23', 'Fill (ROI)', 'blobs(); makeRectangle(200,10,30,30); setForegroundColor(255,255,255); run("Fill", "slice"); result(getPixel(210,20)==255, "pixel="+getPixel(210,20));'),
 ('23b', 'Fill (ROI, no prior setColor)', 'newImage("z","8-bit black",64,64,1); makeRectangle(5,5,20,20); setForegroundColor(255,255,255); run("Fill", "slice"); result(getPixel(10,10)==255, "pixel="+getPixel(10,10));'),
 ('24', 'Clear (ROI)', 'blobs(); makeRectangle(10,10,30,30); setBackgroundColor(0,0,0); run("Clear", "slice"); result(getPixel(30,30)==0, "pixel="+getPixel(30,30));'),
 ('25', 'ROI Manager add/measure', 'blobs(); makeRectangle(10,10,60,60); roiManager("reset"); roiManager("add"); run("Clear Results"); roiManager("measure"); result(roiManager("count")==1 && nResults==1, "count="+roiManager("count")+" rows="+nResults);'),
 ('26', 'Brightness/Contrast...', 'blobs(); run("Brightness/Contrast..."); getMinAndMax(a,b); result(false, "returned; no effect measurable; range "+a+"-"+b);'),
 ('27', 'Threshold...', 'blobs(); run("Threshold..."); getThreshold(lo,hi); result(false, "returned; threshold "+lo+"-"+hi);'),
 ('28', 'Undo', 'blobs(); c00=cks(); run("Invert"); c0=cks(); run("Undo"); result(cks()==c00, "orig="+c00+" inv="+c0+" undo="+cks());'),
 ('29', '8-bit', 'newImage("f","32-bit ramp",64,64,1); run("8-bit"); result(bitDepth()==8, "bitDepth="+bitDepth());'),
 ('30a', 'Save As>Tiff...', 'blobs(); p=TMP+"/p14-"+getTime()+".tif"; run("Tiff...", "path=["+p+"]"); result(File.exists(p), "exists="+File.exists(p));'),
 ('30b', 'File>Open... (run)', 'blobs(); p=TMP+"/p14b.tif"; saveAs("Tiff", p); close("*"); run("Open...", "path=["+p+"]"); n=nImages; t="-"; if (n>0) t=getTitle(); result(n==1 && t=="p14b.tif", "nImages="+n+" title="+t);'),
 ('30c', 'open(path) macro fn', 'blobs(); p=TMP+"/p14c.tif"; saveAs("Tiff", p); close("*"); open(p); n=nImages; t="-"; if (n>0) t=getTitle(); result(n==1 && t=="p14c.tif", "nImages="+n+" title="+t);'),
 ('31', 'Find Maxima...', 'blobs(); run("Clear Results"); run("Find Maxima...", "prominence=10 output=Count"); n=nResults; c=-1; if (n>0) c=getResult("Count",n-1); result(n>=1 && c>=1, "nResults="+n+" Count="+c);'),
 ('32', 'Image Calculator...', 'newImage("a","8-bit ramp",64,64,1); newImage("b","8-bit ramp",64,64,1); run("Image Calculator...", "image1=a operation=Add image2=b create"); result(startsWith(getTitle(),"Result of"), getTitle());'),
 ('33', 'Set Scale...', 'blobs(); run("Set Scale...", "distance=10 known=1 unit=um"); getPixelSize(u,pw,ph); result(abs(pw-0.1)<1e-9, "pw="+pw+" unit="+u);'),
 ('34', 'Add Slice', 'stack3(); run("Add Slice"); result(nSlices==4, "n="+nSlices);'),
 ('35', 'Histogram (stack)', 'stack3(); t=getTitle(); run("Histogram", "bins=256 use x_min=0 x_max=256 y_max=Auto stack"); result(getTitle()!=t, "current="+getTitle());'),
]

def hoist(body):
    """The IJ1 macro language rejects a comparison expression as a function argument
    ("')' expected"), so rewrite result(EXPR, DETAIL) as ok=(EXPR); result(ok, DETAIL)."""
    i = body.rfind('result(')
    j, depth, q = i + 7, 0, False
    while True:
        ch = body[j]
        if ch == '"':
            q = not q
        elif not q and ch == '(':
            depth += 1
        elif not q and ch == ')':
            depth -= 1
        elif not q and ch == ',' and depth == 0:
            break
        j += 1
    return body[:i] + 'ok=(' + body[i + 7:j] + '); result(ok' + body[j:]


rows = []
ONLY = set(sys.argv[4].split(',')) if len(sys.argv) > 4 else None
for oid, name, body in OPS:
    if ONLY and oid not in ONLY:
        continue
    body = hoist(body)
    ijm = os.path.join(WD, 'P14-%s-%s.ijm' % (MODE, oid))
    res = os.path.join(WD, 'P14-%s-%s.result' % (MODE, oid)).replace('\\', '/')
    for p in (res, res + '.started'):
        if os.path.exists(p):
            os.remove(p)
    with open(ijm, 'w', encoding='utf-8') as f:
        f.write(PREFIX + '\n' + body + '\n')
    if len(sys.argv) > 3 and sys.argv[3] == 'gen':
        continue
    log = ijm + '.log'
    t0 = time.time()
    with open(log, 'wb') as lf:
        p = subprocess.Popen([FIJI + r'\fiji-windows-x64.exe', '--java-home=' + JH, '--allow-multiple',
                              '--headless', '-macro', ijm, res], cwd=FIJI, stdout=lf, stderr=subprocess.STDOUT)
        try:
            rc = p.wait(timeout=120)
        except subprocess.TimeoutExpired:
            rc = 'TIMEOUT'
            subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
    el = time.time() - t0
    started = os.path.exists(res + '.started')
    if os.path.exists(res):
        txt = open(res, encoding='utf-8').read().strip()
        status, _, detail = txt.partition('\t')
        status = status.lower()
    else:
        status, detail = ('aborted' if started else 'not-started'), 'no result file (macro aborted at or after the operation)'
    rows.append((MODE, oid, name, status, detail, rc, '%.1f' % el))
    print('\t'.join(map(str, rows[-1])), flush=True)

with open(os.path.join(WD, 'P14-results-%s%s.tsv' % (MODE, '-rerun' if ONLY else '')), 'w', encoding='utf-8') as f:
    f.write('mode\tid\toperation\tstatus\tdetail\trc\telapsed_s\n')
    for r in rows:
        f.write('\t'.join(map(str, r)) + '\n')
