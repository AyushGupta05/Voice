"""P12 - merge the static inventory (P10) with the runtime dump (P11) and the headless
probes (P13/P14/P15); write docs/data/commands.csv, docs/data/commands-core-parameters.csv
and the P12 diff report.

Usage: python -I P12_build_and_diff.py <scratchG1> <repo>
"""
import csv, json, os, re, sys
from collections import Counter, defaultdict

G, REPO = sys.argv[1], sys.argv[2]
sys.path.insert(0, G)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import core_params  # noqa: E402  (scratch name)
except ImportError:  # repository copy is named P12-core-params.py
    import importlib.util
    _spec = importlib.util.spec_from_file_location("core_params", os.path.join(os.path.dirname(os.path.abspath(__file__)), "P12-core-params.py"))
    core_params = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(core_params)

rows = json.load(open(os.path.join(G, 'out', 'static_rows.json'), encoding='utf-8'))


def tsv(path):
    with open(path, encoding='utf-8') as f:
        r = csv.reader(f, delimiter='\t', quoting=csv.QUOTE_NONE)
        head = next(r)
        return [dict(zip(head, x + [''] * (len(head) - len(x)))) for x in r]


RT = os.path.join(G, 'rt')
ij1 = {d['label']: d['value'] for d in tsv(os.path.join(RT, 'P11-ij1-commands.tsv'))}
menu_struct = tsv(os.path.join(RT, 'P11-ij1-menu-structure.tsv'))
mods = tsv(os.path.join(RT, 'P11-modules.tsv'))
mod_by_id = {m['identifier']: m for m in mods}


def norm(v):
    v = (v or '').strip()
    return v[:-4] if v.endswith('("")') else v


# label -> runtime menu path(s) from the hooks' menu structure
path_by_label = defaultdict(list)
for d in menu_struct:
    mp = d['menu_path']
    if re.search(r'>-\d*$', mp):
        continue
    path_by_label[mp.rsplit('>', 1)[-1]].append(mp.rsplit('>', 1)[0] if '>' in mp else '')

# ---------------------------------------------------------------- probes
F = os.path.join(G, 'final')
probe = defaultdict(dict)  # op id -> mode -> (status, detail)
for b in ('false', 'true'):
    p = os.path.join(F, 'p13', 'P13-%s.tsv' % b)
    if os.path.exists(p):
        for d in tsv(p):
            probe[d['id']]['groovy' + ('-batch' if b == 'true' else '')] = (d['status'], d['detail'] or d['exception'])
for m in ('batch', 'nobatch'):
    for p in (os.path.join(F, 'p14', 'P14-results-%s.tsv' % m), os.path.join(F, 'p14', 'P14-results-%s-rerun.tsv' % m)):
        if not os.path.exists(p):
            continue
        for d in tsv(p):
                st = d['status'] + (' (process did not exit; killed at 120 s)' if d['rc'] == 'TIMEOUT' else '')
                probe[d['id']]['macro-cli' + ('-batch' if m == 'batch' else '')] = (st, d['detail'])
p = os.path.join(F, 'P15-inprocess-batch.tsv')
if os.path.exists(p):
    for d in tsv(p):
        oid = re.match(r'P14-batch-(\w+)\.ijm', d['file']).group(1)
        rf = d['result_file']
        st = rf.split(' ', 1)[0].lower() if rf.startswith(('PASS', 'FAIL')) else 'aborted'
        probe[oid]['macro-inproc'] = (st, (rf if st != 'aborted' else d['captured_output'])[:160])

OPMAP = {
    '01': 'ij1:Edit>Invert', '02': 'ij1:Process>Filters>Gaussian Blur...', '03': 'ij1:Process>Subtract Background...',
    '04': 'ij1:Process>Filters>Median...', '05': 'ij1:Process>Binary>Convert to Mask', '06': 'ij1:Image>Adjust>Auto Threshold',
    '08': 'ij1:Analyze>Set Measurements...', '09': 'ij1:Analyze>Measure', '10': 'ij1:Analyze>Analyze Particles...',
    '11': 'ij1:Analyze>Analyze Particles...', '12': 'ij1:Analyze>Histogram', '35': 'ij1:Analyze>Histogram',
    '13': 'ij1:Image>Duplicate...', '14': 'ij1:Image>Crop', '15': 'ij1:Image>Scale...', '16': 'ij1:Image>Transform>Rotate...',
    '17': 'ij1:Image>Stacks>Z Project...', '18': 'ij1:Image>Stacks>Tools>Make Substack...', '19': 'ij1:Image>Color>Split Channels',
    '20': 'ij1:Image>Color>Merge Channels...', '21': 'ij1:Process>Enhance Contrast...', '22': 'ij1:Image>Lookup Tables>Apply LUT',
    '23': 'ij1:Edit>Fill', '23b': 'ij1:Edit>Fill', '24': 'ij1:Edit>Clear', '25': 'ij1:Edit>Selection>Add to Manager',
    '26': 'ij1:Image>Adjust>Brightness/Contrast...', '27': 'ij1:Image>Adjust>Threshold...', '28': 'ij1:Edit>Undo',
    '29': 'ij1:Image>Type>8-bit', '30a': 'ij1:File>Save As>Tiff...', '30b': 'ij1:File>Open...', '30': 'ij1:File>Save As>Tiff...',
    '31': 'ij1:Process>Find Maxima...', '32': 'ij1:Process>Image Calculator...', '33': 'ij1:Analyze>Set Scale...',
    '34': 'ij1:Image>Stacks>Add Slice', '16b': 'ij1:Edit>Selection>Rotate...',
}
cmd_probe = defaultdict(list)
for oid, modes in probe.items():
    if oid in OPMAP:
        cmd_probe[OPMAP[oid]].append((oid, modes))


def probe_summary(cid):
    if cid not in cmd_probe:
        return 'untested', ''
    sts, parts = [], []
    for oid, modes in sorted(cmd_probe[cid]):
        for m, (st, det) in sorted(modes.items()):
            sts.append(st.split(' ')[0])
            parts.append('op%s %s=%s' % (oid, m, st))
    if cid == 'ij1:Edit>Selection>Rotate...':
        return 'untested', 'precondition probe only: no ROI -> "This command requires a selection" error raised as designed; ' + '; '.join(parts)
    if all(s == 'pass' for s in sts):
        v = 'pass'
    elif any(s == 'pass' for s in sts):
        v = 'partial'
    else:
        v = 'fail'
    return v, 'P13/P14/P15: ' + '; '.join(parts)


# ---------------------------------------------------------------- runtime status
counts = Counter()
seen_labels = set()
out = []
for r in rows:
    cid, lab = r['command_id'], r['label']
    rs, rv, crh = '', '', ''
    sk = r['source_kind']
    if r['framework'] in ('IJ1',) and sk in ('IJ_Props', 'Menus.java', 'plugins.config', 'plugins-dir-class'):
        if cid.startswith(('ij1popup:', 'ij1example:')):
            rs = 'n/a (not a command-table entry)'
        elif cid == 'ij1:Process>Repeat Command':
            rs = 'n/a (handled by Executer)'
        else:
            seen_labels.add(lab)
            v = ij1.get(lab)
            impl = r['implementation'] + ('("%s")' % r['arg'] if r['arg'] else '')
            if v is None:
                rs = 'absent'
            elif v.startswith(('command:', 'script:')):
                rs, rv = 'overridden-by-IJ2', v
            elif norm(v) == norm(impl):
                rs, rv = 'present', v
            else:
                rs, rv = 'shadowed (label bound to another class)', v
            rp = path_by_label.get(lab)
            # NB: the headless ij1-patcher menu structure records every plugins.config item under 'Plugins'
            # (observed in P11), so it is only used for IJ_Props/Menus.java rows.
            if rp and sk != 'plugins.config' and r['menu_path'] not in rp:
                r['notes'] = (r['notes'] + '; ' if r['notes'] else '') + 'runtime menu path: ' + ' | '.join(sorted(set(rp)))
    elif sk == 'luts-dir' or cid.startswith('ij1example:'):
        rs = 'menu-only (resolved by Executer, not in command table)'
    elif cid.startswith('ij1macro:'):
        rs = 'suppressed (imagej-legacy LegacyService.java#L776 suppressIJ1ScriptDiscovery)'
    elif r['framework'] == 'script':
        rel = r['implementation'].split('/', 1)[1] if r['implementation'].startswith(('scripts/', 'plugins/')) else r['implementation']
        if r['source_kind'] == 'jar-scripts-resource':
            rel = r['implementation'][len('scripts/'):]
        m = mod_by_id.get('script:' + rel)
        if m:
            crh = m['can_run_headless']
            in_ij1 = ij1.get(m['title'] if m['title'] else lab) == 'script:' + rel
            rs = 'module' + (' + IJ1 command table' if in_ij1 else ' only (file name lacks "_", LegacyService.java#L678)')
            rv = 'script:' + rel
            if m['menu_path'] and m['menu_path'] != (r['menu_path'] + '>' + lab):
                r['notes'] += '; runtime menu path: ' + m['menu_path']
        else:
            rs = 'absent (no ScriptLanguage for extension, or not scanned)'
    elif r['framework'] == 'IJ2':
        m = mod_by_id.get('command:' + r['implementation'])
        if m:
            crh = m['can_run_headless']
            leaf = m['menu_path'].rsplit('>', 1)[-1] if m['menu_path'] else ''
            in_ij1 = leaf and ij1.get(leaf) == 'command:' + r['implementation']
            rs = 'module' + (' + IJ1 command table' if in_ij1 else '')
            rv = 'command:' + r['implementation']
        else:
            rs = 'absent from ModuleService (blocklisted no-legacy, non-Command type or failed to load)'
    elif r['framework'] in ('macro-tool', 'IJ1-tool'):
        rs = 'n/a (GUI tool; not run headless)'
    hp, hn = probe_summary(cid) if r['framework'] == 'IJ1' else ('untested', '')
    r['headless_probe'] = hp
    if hn:
        r['notes'] = (r['notes'] + '; ' if r['notes'] else '') + hn
    r['runtime_status'], r['runtime_value'], r['can_run_headless'] = rs, rv, crh
    cn, pre = core_params.CMD.get(cid, ('', ''))
    r['creates_new_image'], r['preconditions'] = cn, pre
    counts[(r['framework'], sk, rs.split(' (')[0])] += 1
    out.append(r)

# runtime-only IJ1 table entries
static_scripts = {x['runtime_value'] for x in out if x['runtime_value']}
for lab, v in sorted(ij1.items()):
    if lab in seen_labels or v in static_scripts:
        continue
    if v.startswith(('command:', 'script:')):
        m = mod_by_id.get(v)
        mp = m['menu_path'] if m else ''
        out.append(dict(command_id='runtime:' + v, label=lab, menu_path=mp.rsplit('>', 1)[0] if '>' in mp else mp, shortcut='',
                        framework='IJ2' if v.startswith('command:') else 'script', implementation=v.split(':', 1)[1], arg='',
                        source_artifact='probes/P11-ij1-commands.tsv', source_kind='runtime-only', headless_probe='untested',
                        notes='inserted into IJ1 command table by IJ1Helper.addMenuItems (IJ1Helper.java#L874-L954)',
                        runtime_status='IJ1 command table', runtime_value=v, can_run_headless=m['can_run_headless'] if m else '',
                        creates_new_image='', preconditions=''))
    else:
        out.append(dict(command_id='runtime:ij1:' + lab, label=lab, menu_path=' | '.join(sorted(set(path_by_label.get(lab, [])))) or '(no menu)',
                        shortcut='', framework='IJ1', implementation=v, arg='', source_artifact='probes/P11-ij1-commands.tsv',
                        source_kind='runtime-only', headless_probe='untested', notes='present at runtime, not found by static parse',
                        runtime_status='IJ1 command table', runtime_value=v, can_run_headless='', creates_new_image='', preconditions=''))
    counts[('runtime-only', v.split(':')[0] if ':' in v and v.split(':')[0] in ('command', 'script') else 'ij1')] += 1

# unique ids
idc = Counter(x['command_id'] for x in out)
dup_ids = {k for k, v in idc.items() if v > 1}
seen = Counter()
for x in out:
    if x['command_id'] in dup_ids:
        seen[x['command_id']] += 1
        x['command_id'] = '%s#%d' % (x['command_id'], seen[x['command_id']])
        x['notes'] = (x['notes'] + '; ' if x['notes'] else '') + 'DUPLICATE command_id base; suffixed #n in source order'

COLS = ['command_id', 'label', 'menu_path', 'shortcut', 'framework', 'implementation', 'arg', 'source_artifact', 'source_kind',
        'headless_probe', 'notes', 'runtime_status', 'runtime_value', 'can_run_headless', 'creates_new_image', 'preconditions']
os.makedirs(os.path.join(REPO, 'docs', 'data'), exist_ok=True)
with open(os.path.join(REPO, 'docs', 'data', 'commands.csv'), 'w', newline='', encoding='utf-8') as f:
    w = csv.DictWriter(f, fieldnames=COLS, extrasaction='ignore', lineterminator='\n')
    w.writeheader()
    for x in out:
        w.writerow(x)
PC = ['command_id', 'parameter_key', 'type', 'default', 'range_or_choices', 'recorded_as', 'source', 'consequential', 'native_unit', 'hidden_global_dependency']
ids = {x['command_id'] for x in out}
with open(os.path.join(REPO, 'docs', 'data', 'commands-core-parameters.csv'), 'w', newline='', encoding='utf-8') as f:
    w = csv.DictWriter(f, fieldnames=PC, lineterminator='\n')
    w.writeheader()
    for p in core_params.P:
        w.writerow(p)
missing_core = sorted({p['command_id'] for p in core_params.P} - ids) + sorted(set(core_params.CMD) - ids)

# ---------------------------------------------------------------- report
rep = []
rep.append('commands.csv rows: %d' % len(out))
rep.append('by framework: %s' % dict(Counter(x['framework'] for x in out)))
rep.append('by source_kind: %s' % dict(Counter(x['source_kind'] for x in out)))
rep.append('by (framework, source_kind, runtime_status): ')
for k, v in sorted(Counter((x['framework'], x['source_kind'], x['runtime_status'].split(' (')[0]) for x in out).items()):
    rep.append('   %s: %d' % (' / '.join(k), v))
rep.append('headless_probe: %s' % dict(Counter(x['headless_probe'] for x in out)))
rep.append('core commands missing from commands.csv: %s' % missing_core)
rep.append('core parameter rows: %d for %d commands' % (len(core_params.P), len({p["command_id"] for p in core_params.P})))
rep.append('duplicate command_id bases: %d %s' % (len(dup_ids), sorted(dup_ids)[:40]))
# duplicate labels in static IJ1 sources (same label from different implementations)
lab_impl = defaultdict(set)
for x in out:
    if x['framework'] == 'IJ1' and x['source_kind'] in ('IJ_Props', 'Menus.java', 'plugins.config', 'plugins-dir-class') and not x['command_id'].startswith(('ij1popup', 'ij1example')):
        lab_impl[x['label']].add((x['implementation'] + ('("%s")' % x['arg'] if x['arg'] else ''), x['source_artifact'].split('!')[0].split('#')[0]))
dups = {k: v for k, v in lab_impl.items() if len(v) > 1}
rep.append('IJ1 labels defined more than once (static): %d' % len(dups))
for k, v in sorted(dups.items()):
    rep.append('   %r -> runtime %r <= %s' % (k, ij1.get(k), sorted(v)))
over = [(x['label'], x['runtime_value']) for x in out if x['runtime_status'] == 'overridden-by-IJ2']
rep.append('IJ1 labels overridden by IJ2 commands/scripts: %d %s' % (len(over), over))
# effective menu tree (IJ1 structure + inserted IJ2/script items)
eff = []
# IJ1 leaves: declared (static) menu path of rows live in the runtime command table; the headless
# hook-recorded structure flattens plugins.config items to 'Plugins' and is not used here.
for x in out:
    if x['framework'] == 'IJ1' and x['runtime_status'].split(' (')[0] in ('present', 'shadowed', 'overridden-by-IJ2'):
        eff.append(x['menu_path'].strip() + '>' + x['label'].strip())
    elif x['command_id'].startswith(('ij1popup:', 'ij1example:', 'ij1lut:')) or x['command_id'] == 'ij1:Process>Repeat Command':
        pass
flat = sum(1 for d in menu_struct if d['menu_path'].startswith('Plugins>') and d['menu_path'].count('>') == 1)
rep.append('headless hook menu structure: %d entries, %d flat Plugins>leaf entries' % (len(menu_struct), flat))
for lab, v in ij1.items():
    if v.startswith(('command:', 'script:')) and v in mod_by_id and mod_by_id[v]['menu_path']:
        eff.append(mod_by_id[v]['menu_path'])
eff = sorted(set(eff))
eff = [re.sub(r'\s*>\s*', '>', e) for e in eff]
eff = sorted(set(eff))
lvl1 = Counter(p.split('>')[0] for p in eff)
lvl2 = Counter('>'.join(p.split('>')[:2]) for p in eff if p.count('>') >= 2)
direct = Counter(p.split('>')[0] for p in eff if p.count('>') == 1)
rep.append('effective runtime menu leaves: %d' % len(eff))
order = ['File', 'Edit', 'Image', 'Process', 'Analyze', 'Plugins', 'Window', 'Help']
for top in order + sorted(set(lvl1) - set(order)):
    subs = sorted((k.split('>')[1], v) for k, v in lvl2.items() if k.split('>')[0] == top)
    rep.append('MENU %s\tleaves=%d\tdirect=%d\tsubmenus=%s' % (top, lvl1[top], direct[top], '; '.join('%s (%d)' % s for s in subs)))
# IJ2 static vs runtime
sj = [x for x in out if x['framework'] == 'IJ2' and x['source_kind'] == 'scijava-index']
rep.append('IJ2 static rows: %d; runtime status: %s' % (len(sj), dict(Counter(x['runtime_status'] for x in sj))))
rep.append('IJ2 static rows with menu path: %d; of these in IJ1 table: %d' % (sum(1 for x in sj if x['menu_path']), sum(1 for x in sj if 'IJ1 command table' in x['runtime_status'])))
rep.append('modules: %s' % dict(Counter(m['info_class'] for m in mods)))
rep.append('modules canRunHeadless=true: %s' % [m['identifier'] for m in mods if m['can_run_headless'] == 'true'])
rep.append('no-legacy modules present after removal: %d' % sum(1 for m in mods if m['no_legacy'] == 'true'))
rep.append('probe matrix:')
for oid in sorted(probe, key=lambda s: (len(s.rstrip('abc')), s)):
    rep.append('   %s\t%s\t%s' % (oid, OPMAP.get(oid, '(API/macro function)'), '; '.join('%s=%s [%s]' % (m, s, d[:90]) for m, (s, d) in sorted(probe[oid].items()))))
txt = '\n'.join(rep)
open(os.path.join(G, 'final', 'P12-diff-report.txt'), 'w', encoding='utf-8').write(txt + '\n')
json.dump({'menu_leaves': eff}, open(os.path.join(G, 'final', 'P12-effective-menu.json'), 'w', encoding='utf-8'), indent=0)
print(txt)
