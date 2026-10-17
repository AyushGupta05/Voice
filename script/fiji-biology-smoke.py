#!/usr/bin/env python3
"""Live check of Penny's biology operations against a running Fiji bridge, on the open samples from
script/fetch-samples.sh. Each step goes through the recorded-decision envelope (approved + expectedContext), prints the
real numbers and checks that every visible result carries a highlight inside the canvas. Opens sample images; changes no
files except the workflow/exports folders under .local."""
import json, os, sys, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLES = os.path.join(ROOT, '.local', 'data', 'samples')
TOKEN = open(os.path.join(ROOT, '.local', 'state', 'bridge-token')).read().strip()
PORT = int(os.environ.get('LUMA_FIJI_PORT', '49557'))
failures = []


def call(operation, arguments=None, mutate=False):
    body = {'operation': operation, 'arguments': arguments or {}}
    if mutate:
        body['approved'] = True
        body['expectedContext'] = call('context')['contextKey']
    request = urllib.request.Request(f'http://127.0.0.1:{PORT}/execute', json.dumps(body).encode(), method='POST',
                                     headers={'Authorization': f'Bearer {TOKEN}', 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.load(response)['result']
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'{operation}: {json.load(error).get("error")}') from None


def highlight_ok(name, result):
    targets = (result.get('highlight') or {}).get('targets') or []
    canvas = (call('context').get('viewer') or {}).get('canvasScreen') or {}
    if not targets:
        failures.append(f'{name}: no highlight targets'); return 0
    if canvas:
        inside = [t for t in targets if canvas['x'] - 2 <= t['x'] and t['x'] + t['width'] <= canvas['x'] + canvas['width'] + 2
                  and canvas['y'] - 2 <= t['y'] and t['y'] + t['height'] <= canvas['y'] + canvas['height'] + 2]
        if len(inside) < len(targets):
            failures.append(f'{name}: {len(targets) - len(inside)} of {len(targets)} highlight targets fall outside the canvas {canvas}')
    return len(targets)


def step(name, fn):
    try:
        summary = fn()
        print(f'PASS {name}: {summary}')
    except Exception as error:  # report and keep going: one sample must not hide the others
        failures.append(f'{name}: {error}'); print(f'FAIL {name}: {error}')


def open_sample(*parts):
    path = os.path.join(SAMPLES, *parts)
    if not os.path.exists(path):
        raise RuntimeError(f'missing {path}; run script/fetch-samples.sh')
    return call('open_path', {'path': path}, mutate=True)


def tracking():
    open_sample('mitosis', 'mitosis.tif')
    result = call('track_objects', {'channel': 2}, mutate=True)
    return f"{result['tracks']} tracks over {result['frames']} frames, mean speed {result['meanSpeed']} {result.get('speedUnit')}, {highlight_ok('track', result)} highlighted"


def intensity():
    call('select_roi', {'type': 'oval', 'x': 60, 'y': 70, 'width': 50, 'height': 50}, mutate=False)
    result = call('intensity_over_time', {'channel': 1}, mutate=True)
    series = result['series'][0]
    return f"{result['frames']} frames, {series['first']:.1f} → {series['last']:.1f} ({series['changePercent']}%)"


def tumour():
    open_sample('he-breast-idc', 'Histopathology_of_invasive_ductal_carcinoma_intermediate_magnification.jpg')
    result = call('tumor_region', {}, mutate=True)
    return f"{result['regions']} regions, {result['areaFraction'] * 100:.1f}% of {result.get('areaFractionOf')}, {highlight_ok('tumour', result)} highlighted"


def coloc():
    open_sample('hela-cells', 'hela-cells.tif')
    result = call('colocalize', {'channelA': 1, 'channelB': 2})
    return f"Pearson {result['pearson']:.3f}, M1 {result['manders']['m1']:.2f}, M2 {result['manders']['m2']:.2f} ({result['interpretation']})"


def positivity():
    open_sample('fluorescent-cells', 'FluorescentCells.tif')
    seg = call('segment_cells', {'channel': 3}, mutate=True)
    result = call('classify_intensity', {'channel': 2, 'label': 'Marker'}, mutate=True)
    stats = call('object_stats', {})
    return f"{seg['count']} nuclei, {result['positive']}/{result['total']} positive ({result['percentPositive']:.1f}%), mean area {stats['area']['mean']:.1f}, {highlight_ok('positivity', result)} highlighted"


def macros():
    code = 'run("8-bit");\nsetAutoThreshold("Otsu dark");\nrun("Convert to Mask");\nrun("Analyze Particles...", "size=" + minSize + "-Infinity display clear");\nprint("count " + nResults);'
    open_sample('blobs', 'blobs.gif')
    made = call('make_macro', {'name': 'penny smoke count', 'code': code.replace('dark', ''), 'params': {'minSize': 20}}, mutate=True)
    if not made['ok']:
        raise RuntimeError(f"make_macro failed: {made.get('error')} line {made.get('errorLine')}")
    folder = os.path.join(SAMPLES, 'bbbc008', 'images')
    run = call('run_workflow', {'name': 'penny-smoke-count', 'target': 'folder', 'folder': folder, 'pattern': '*channel1*', 'limit': 6}, mutate=True)
    return f"macro ok ({made['resultsAdded']} rows); batch processed {run['processed']}, failed {len(run['failed'])}, csv {os.path.basename(run.get('csv') or '-')}"


for name, fn in [('track_objects (mitosis)', tracking), ('intensity_over_time', intensity), ('tumor_region (H&E)', tumour),
                 ('colocalize (HeLa)', coloc), ('segment + classify + stats', positivity), ('make_macro + run_workflow', macros)]:
    step(name, fn)
print('\n'.join(['', 'FAILURES:'] + failures) if failures else '\nAll live biology checks passed.')
sys.exit(1 if failures else 0)
