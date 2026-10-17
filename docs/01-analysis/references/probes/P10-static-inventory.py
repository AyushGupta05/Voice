"""P10 - static command/menu inventory of the pinned Fiji install.

Reads (never extracts) jar entries with zipfile, mirroring the parsing rules of
imagej/ImageJ@v1.54p:ij/Menus.java (addMenuBar, addSubMenu, addPluginItem,
installJarPlugin, getPlugins, installMacro, convertShortcutToCode),
scijava-common@2.101.0 PluginInfo/ScriptFinder and imagej-legacy@3.0.0
LegacyService#L483-L489 (script directories).

Usage: python -I P10_static_inventory.py <FijiDir> <ImageJSrcDir> <outDir>
Outputs: static_rows.json, scijava_index_all.csv, plugins_config_raw.csv,
         P10-summary.txt
"""
import csv, json, os, re, sys, zipfile
from collections import Counter, defaultdict

FIJI, IJSRC, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
rows = []
log = []

# ---------------------------------------------------------------- shortcuts
VK = {**{chr(c): c for c in range(65, 91)}, **{str(d): 48 + d for d in range(10)}}
VK_F1, VK_F10, VK_NUMPAD0 = 112, 121, 96


def convert_shortcut_to_code(s):
    """Port of Menus.convertShortcutToCode (Menus.java#L1553-L1593)."""
    n = len(s)
    if n == 2 and s[0] == 'F':
        code = VK_F1 + ord(s[1]) - 49
        return code if VK_F1 <= code <= VK_F1 + 8 else 0
    if n == 3 and s[0] == 'F':
        code = VK_F10 + ord(s[2]) - 48
        return code if VK_F10 <= code <= VK_F10 + 2 else 0
    if n == 2 and s[0] == 'N':
        code = VK_NUMPAD0 + ord(s[1]) - 48
        if VK_NUMPAD0 <= code <= VK_NUMPAD0 + 9:
            return code
        return {'/': 111, '*': 106, '-': 109, '+': 107, '.': 110}.get(s[1], 0)
    if n != 1:
        return 0
    c = ord(s)
    if 65 <= c <= 90:
        return 65 + c - 65 + 200
    if 97 <= c <= 122:
        return 65 + c - 97
    if 48 <= c <= 57:
        return 48 + c - 48
    return 0


def describe_code(code, shift):
    if VK_F1 <= code <= VK_F1 + 11:
        return 'F%d' % (code - VK_F1 + 1)
    if VK_NUMPAD0 <= code <= VK_NUMPAD0 + 9:
        k = 'NumPad%d' % (code - VK_NUMPAD0)
    elif code in (106, 107, 109, 110, 111):
        k = 'NumPad' + {106: '*', 107: '+', 109: '-', 110: '.', 111: '/'}[code]
    else:
        k = chr(code)
    return ('Ctrl+Shift+' if shift else 'Ctrl+') + k


def add_plugin_item(s):
    """Port of Menus.addPluginItem (Menus.java#L516-L556).
    Returns (command, shortcut_desc, className) or None for separators."""
    if s.startswith('"-"'):
        return None
    last = s.rfind(',')
    if last <= 0:
        return None
    command = s[1:last - 1]
    key, shift, sc = 0, False, ''
    if command.endswith(']'):
        ob = command.rfind('[')
        if ob > 0:
            shortcut = command[ob + 1:-1]
            key = convert_shortcut_to_code(shortcut)
            fkey = VK_F1 <= key <= VK_F1 + 11
            if key > 0 and not fkey:
                command = command[:ob]
            if key == 0:
                sc = 'label-hint[%s]' % shortcut
    if VK_F1 <= key <= VK_F1 + 11:
        sc = describe_code(key, False)
        key = 0
    elif 265 <= key <= 290:
        key -= 200
        shift = True
    if key:
        sc = describe_code(key, shift)
    rest = s[last + 1:].lstrip(' ')
    return command, sc, rest


def split_class_arg(cls):
    m = re.match(r'^(.*?)\("(.*)"\)$', cls.strip())
    if m:
        return m.group(1), m.group(2)
    return cls.strip(), ''


def add(**kw):
    base = dict(command_id='', label='', menu_path='', shortcut='', framework='',
                implementation='', arg='', source_artifact='', source_kind='',
                headless_probe='untested', notes='')
    base.update(kw)
    rows.append(base)

# ---------------------------------------------------------------- 1. IJ_Props.txt
ijjar = os.path.join(FIJI, 'jars', 'ij-1.54p.jar')
with zipfile.ZipFile(ijjar) as z:
    props_text = z.read('IJ_Props.txt').decode('latin-1')
# prefix -> menu path (derived from Menus.addMenuBar getMenu(...,true) calls and
# addSubMenu key = lower-case first word of submenu name, Menus.java#L467-L489)
PREFIX = {
    'new': 'File>New', 'open': 'File>Open Samples', 'import': 'File>Import',
    'save': 'File>Save As', 'selection': 'Edit>Selection', 'options': 'Edit>Options',
    'adjust': 'Image>Adjust', 'color': 'Image>Color', 'stacks': 'Image>Stacks',
    'animation_': 'Image>Stacks>Animation', 'tools_': 'Image>Stacks>Tools',
    'hyperstacks': 'Image>Hyperstacks', 'transform': 'Image>Transform',
    'zoom': 'Image>Zoom', 'overlay': 'Image>Overlay', 'lookup': 'Image>Lookup Tables',
    'noise': 'Process>Noise', 'shadows': 'Process>Shadows', 'binary': 'Process>Binary',
    'math': 'Process>Math', 'fft': 'Process>FFT', 'filters': 'Process>Filters',
    'batch': 'Process>Batch', 'gels': 'Analyze>Gels', 'tools': 'Analyze>Tools',
    'plug-in': 'Plugins', 'macros': 'Plugins>Macros', 'shortcuts': 'Plugins>Shortcuts',
    'utilities': 'Plugins>Utilities', 'new_': 'Plugins>New', 'about': 'Help>About Plugins',
    'popup': 'Popup',
}
props_sections = Counter()
other_props = []
for ln, line in enumerate(props_text.splitlines(), 1):
    t = line.strip()
    if not t or t.startswith('#') or '=' not in t:
        continue
    key, val = t.split('=', 1)
    val = val.replace('\\\\', '\\')  # java.util.Properties unescape (only case present)
    m = re.match(r'^([a-z_\-]+?)(\d\d)$', key)
    if not m or m.group(1) not in PREFIX:
        other_props.append((ln, key, val))
        continue
    pre = m.group(1)
    menu = PREFIX[pre]
    props_sections[pre] += 1
    src = 'Fiji/jars/ij-1.54p.jar!/IJ_Props.txt#L%d' % ln
    if pre == 'popup':
        if val != '-':
            add(command_id='ij1popup:' + val.strip(), label=val, menu_path='Popup',
                framework='IJ1', implementation='(reference to existing command)',
                source_artifact=src, source_kind='IJ_Props',
                notes='image right-click popup entry; resolves to the command of the same label; may be overridden by StartupMacros "Popup Menu" macro')
        continue
    if val == '-' or val.startswith('"-"'):
        continue
    if val.startswith('>'):
        log.append('submenu marker %s=%s' % (key, val))
        continue
    r = add_plugin_item(val)
    if not r:
        continue
    cmd, sc, cls = r
    c, a = split_class_arg(cls)
    notes = []
    if cmd != cmd.strip():
        notes.append('label has leading/trailing space; IJ.run key must match exactly')
    if pre == 'open':
        notes.append('downloads sample from images.location=http://imagej.net/images/ (network)')
    add(command_id='ij1:%s>%s' % (menu, cmd.strip()), label=cmd, menu_path=menu,
        shortcut=sc, framework='IJ1', implementation=c, arg=a, source_artifact=src,
        source_kind='IJ_Props', notes='; '.join(notes))

# ---------------------------------------------------------------- 2. Menus.java hard-coded
menus_src = open(os.path.join(IJSRC, 'ij', 'Menus.java'), encoding='utf-8').read().splitlines()
VAR = {'file': 'File', 'edit': 'Edit', 'image': 'Image', 'imageType': 'Image>Type',
       'process': 'Process', 'analyzeMenu': 'Analyze', 'window': 'Window', 'help': 'Help',
       'showFolderMenu': 'File>Show Folder', 'openSamples': 'File>Open Samples',
       'examplesMenu': 'Help>Examples'}
pat_item = re.compile(r'add(?:PlugIn|Checkbox)Item\((\w+),\s*"((?:[^"\\]|\\.)*)"(\+key\+"\]")?,\s*"((?:[^"\\]|\\.)*)"(?:\+IJ\.URL2\+"((?:[^"\\]|\\.)*)")?(?:,\s*(KeyEvent\.VK_\w+|0),\s*(true|false))?\)')
seen_hist = False
for ln in range(95, 270):
    line = menus_src[ln - 1]
    for m in pat_item.finditer(line):
        var, label = m.group(1), m.group(2)
        if m.group(3):
            label = label + 'enter]'  # Windows: "Main Window [enter]" (Menus.java#L235)
        cls = m.group(4)
        if m.group(5) is not None:
            cls = cls + 'http://imagej.net/ij' + m.group(5)  # IJ.URL2 (IJ.java#L48)
        cls = cls.replace('\\"', '"')
        vk, shift = m.group(6), m.group(7) == 'true'
        if label == 'Histogram':
            if seen_hist:
                continue  # first occurrence is macOS branch (L221); keep non-Mac L224
            seen_hist = True
            continue
        sc = ''
        if vk and vk != '0':
            k = vk.replace('KeyEvent.VK_', '')
            k = {'CLOSE_BRACKET': ']'}.get(k, k)
            sc = ('Ctrl+Shift+' if shift else 'Ctrl+') + k
        c, a = split_class_arg(cls)
        kind = 'checkbox item' if 'Checkbox' in m.group(0) else ''
        add(command_id='ij1:%s>%s' % (VAR[var], label.strip()), label=label, menu_path=VAR[var],
            shortcut=sc, framework='IJ1', implementation=c, arg=a,
            source_artifact='imagej/ImageJ@v1.54p:ij/Menus.java#L%d' % ln,
            source_kind='Menus.java', notes=kind)
# Histogram non-Mac line
for ln in range(215, 230):
    if 'addPlugInItem(analyzeMenu, "Histogram"' in menus_src[ln - 1] and 'VK_H' in menus_src[ln - 1]:
        add(command_id='ij1:Analyze>Histogram', label='Histogram', menu_path='Analyze', shortcut='Ctrl+H',
            framework='IJ1', implementation='ij.plugin.Histogram',
            source_artifact='imagej/ImageJ@v1.54p:ij/Menus.java#L%d' % ln, source_kind='Menus.java',
            notes='macOS: no menu accelerator; Ctrl+H via shortcuts table (L221-L222)')
add(command_id='ij1:Process>Repeat Command', label='Repeat Command', menu_path='Process', shortcut='Ctrl+R',
    framework='IJ1', implementation='(Executer: re-runs previousCommand)',
    source_artifact='imagej/ImageJ@v1.54p:ij/Menus.java#L207', source_kind='Menus.java',
    notes='addItem only; not in pluginsTable; handled by Executer(String,ImagePlus) startsWith("Repeat")')
for ln in range(270, 280):
    m = re.search(r'pluginsTable\.put\("([^"]+)", "ij\.plugin\.URLOpener\(\\"([^\\]+)\\"\)"\)', menus_src[ln - 1])
    if m:
        add(command_id='ij1:(hidden)>%s' % m.group(1), label=m.group(1), menu_path='(no menu)',
            framework='IJ1', implementation='ij.plugin.URLOpener', arg=m.group(2),
            source_artifact='imagej/ImageJ@v1.54p:ij/Menus.java#L%d' % ln, source_kind='Menus.java',
            notes='command table only (deleted sample image); network')
# Help>Examples (addExample: action command = file name, not in pluginsTable)
sub = None
for ln in range(286, 400):
    line = menus_src[ln - 1]
    m = re.search(r'new Menu\("([^"]+)"\)', line)
    if m and m.group(1) != 'Examples':
        sub = m.group(1)
    m = re.search(r'addExample\(submenu, "([^"]+)", "([^"]+)"\)', line)
    if m:
        add(command_id='ij1example:Help>Examples>%s>%s' % (sub, m.group(1)), label=m.group(1),
            menu_path='Help>Examples>' + sub, framework='IJ1',
            implementation='ij.plugin.frame.Editor.openExample', arg=m.group(2),
            source_artifact='imagej/ImageJ@v1.54p:ij/Menus.java#L%d' % ln, source_kind='Menus.java',
            notes='opens example source in Editor (Executer.runCommand GUI-only branch); not in Menus.getCommands()')

# ---------------------------------------------------------------- 3. luts/ -> Image>Lookup Tables (Menus.addLuts)
lutdir = os.path.join(FIJI, 'luts')
for name in sorted(os.listdir(lutdir)):
    if name.endswith('.lut'):
        lab = name[:-4]
        if '_' in lab and ' ' not in lab:
            lab = lab.replace('_', ' ')
        add(command_id='ij1lut:Image>Lookup Tables>%s' % lab, label=lab, menu_path='Image>Lookup Tables',
            framework='IJ1', implementation='ij.Executer.loadLut', arg='luts/' + name,
            source_artifact='Fiji/luts/' + name, source_kind='luts-dir',
            notes='menu item only (Menus.addLuts L499-L522); not in Menus.getCommands(); resolved by Executer.loadLut')

# ---------------------------------------------------------------- 4. plugins/ directory (Menus.getPlugins)
SCRIPT_EXT_IJ1 = ('.ijm', '.js', '.bsh', '.py')
plugins_dir = os.path.join(FIJI, 'plugins')
ij1_jars = []        # (abs path, submenu or None)
user_classes = []    # (rel class, submenu)
macro_files = []     # rel path with '/'


def valid_macro_name(n, us):
    return (us and n.endswith('.txt')) or n.endswith(SCRIPT_EXT_IJ1)


for name in sorted(os.listdir(plugins_dir)):
    p = os.path.join(plugins_dir, name)
    us = '_' in name
    if us and name.endswith('.class') and '$' not in name:
        user_classes.append((name[:-6], None))
    elif us and name.endswith(('.jar', '.zip')):
        ij1_jars.append((p, None))
    elif valid_macro_name(name, us):
        macro_files.append(name)
    elif os.path.isdir(p) and not name.endswith('.java'):
        for n2 in sorted(os.listdir(p)):
            p2 = os.path.join(p, n2)
            us2 = '_' in n2
            if us2 and n2.endswith('.class') and '$' not in n2:
                user_classes.append((name + '/' + n2[:-6], name))
            elif us2 and n2.endswith(('.jar', '.zip')):
                ij1_jars.append((p2, name))
            elif valid_macro_name(n2, us2):
                macro_files.append(name + '/' + n2)
            elif os.path.isdir(p2) and not (name + '/' + n2).endswith('Launchers'):
                for n3 in sorted(os.listdir(p2)):
                    if valid_macro_name(n3, '_' in n3):
                        macro_files.append(name + '/' + n2 + '/' + n3)
log.append('plugins/: %d IJ1 jars, %d loose classes, %d IJ1 macro/script files' % (len(ij1_jars), len(user_classes), len(macro_files)))

for rel in macro_files:
    parts = rel.split('/')
    menu = 'Plugins' if len(parts) == 1 else 'Plugins>' + '>'.join(parts[:-1])
    cmd = parts[-1].replace('_', ' ')
    cmd = cmd[:-3] if cmd.endswith(('.js', '.py')) else cmd[:-4]
    cmd = cmd.strip()
    add(command_id='ij1macro:%s>%s' % (menu, cmd), label=cmd, menu_path=menu, framework='script',
        implementation='ij.plugin.Macro_Runner', arg=rel, source_artifact='Fiji/plugins/' + rel,
        source_kind='plugins-dir-script',
        notes='IJ1 Menus.installMacro (L668-L699); " Macro" appended if label collides; also discovered by SciJava ScriptService (plugins dir, prefix Plugins)')
for rel, sub in user_classes:
    add(command_id='ij1:Plugins>%s' % rel.split('/')[-1].replace('_', ' '),
        label=rel.split('/')[-1].replace('_', ' '), menu_path='Plugins' + ('>' + sub if sub else ''),
        framework='IJ1', implementation=rel.replace('/', '.'), source_artifact='Fiji/plugins/' + rel + '.class',
        source_kind='plugins-dir-class', notes='Menus.installUserPlugin')

# ---------------------------------------------------------------- 5. all jars: plugins.config, scijava index, scripts/
all_jars = []
for base in ('jars', 'plugins'):
    for dp, dn, fn in os.walk(os.path.join(FIJI, base)):
        for f in fn:
            if f.endswith('.jar'):
                all_jars.append(os.path.join(dp, f))
all_jars.sort()
ij1_jar_set = {os.path.normpath(p): s for p, s in ij1_jars}
pc_raw = []
sj_all = []
jar_scripts = []
autogen = []
for jp in all_jars:
    rel = os.path.relpath(jp, FIJI).replace('\\', '/')
    try:
        z = zipfile.ZipFile(jp)
    except Exception as e:
        log.append('cannot open %s: %s' % (rel, e))
        continue
    names = z.namelist()
    cfgs = [n for n in names if n.endswith('plugins.config')]
    in_plugins = os.path.normpath(jp) in ij1_jar_set
    submenu_dir = ij1_jar_set.get(os.path.normpath(jp))
    if cfgs:
        cfg = cfgs[0]  # Menus.getConfigurationFile returns first entry ending with plugins.config
        text = z.read(cfg).decode('utf-8', 'replace')
        for ln, s in enumerate(text.splitlines(), 1):
            if len(s) < 3 or s.startswith('#'):
                continue
            s0 = s.strip()
            pc_raw.append((rel, cfg, ln, s0))
            # menu resolution: Menus.installJarPlugin (L764-L818)
            if s0.startswith('Plugins>'):
                fc = s0.find(',')
                menu = None if (fc == -1 or fc <= 8) else 'Plugins>' + s0[8:fc]
            elif s0.startswith('"') or s0.startswith('Plugins'):
                menu = 'Plugins>' + submenu_dir if (in_plugins and submenu_dir) else 'Plugins'
            else:
                fq = s0.find('"')
                name = s0 if fq < 0 else s0[:fq].strip()
                if ',' in name:
                    name = name[:name.find(',')]
                if name.startswith('Help>About'):
                    name = 'Help>About Plugins'
                menu = name
            fq = s0.find('"')
            if fq == -1:
                log.append('no quote in %s:%d %s' % (rel, ln, s0))
                continue
            r = add_plugin_item(s0[fq:])
            if not r:
                continue
            cmd, sc, cls = r
            c, a = split_class_arg(cls)
            notes = []
            if not in_plugins:
                notes.append('jar outside plugins/ (not read by vanilla IJ1 getPlugins; installed only if the legacy layer adds it - see runtime diff)')
            if '"' in cmd:
                notes.append('PARSE-ANOMALY label contains quote')
            add(command_id='ij1:%s>%s' % (menu, cmd.strip()), label=cmd, menu_path=menu or '(none)',
                shortcut=sc, framework='IJ1', implementation=c, arg=a,
                source_artifact='Fiji/%s!/%s#L%d' % (rel, cfg, ln), source_kind='plugins.config',
                notes='; '.join(notes))
    elif in_plugins:
        # Menus.autoGenerateConfigFile (L950-L985)
        for n in names:
            if n.endswith('.class') and n.find('_') > 0 and '$' not in n and '/_' not in n and not n.startswith('_'):
                if n[0].islower() and '/' in n:
                    continue
                cn = n[:-6]
                sl = cn.rfind('/')
                menu = 'Plugins' + ('>' + cn[:sl].replace('/', '>').replace('_', ' ') if sl >= 0 else '')
                lab = cn[sl + 1:].replace('_', ' ')
                autogen.append(rel)
                add(command_id='ij1:%s>%s' % (menu, lab), label=lab, menu_path=menu, framework='IJ1',
                    implementation=cn.replace('/', '.'), source_artifact='Fiji/' + rel + '!/' + n,
                    source_kind='plugins.config', notes='auto-generated config (jar has no plugins.config)')
    if 'META-INF/json/org.scijava.plugin.Plugin' in names:
        raw = z.read('META-INF/json/org.scijava.plugin.Plugin').decode('utf-8', 'replace')
        dec = json.JSONDecoder()
        i = 0
        while i < len(raw):
            while i < len(raw) and raw[i].isspace():
                i += 1
            if i >= len(raw):
                break
            obj, j = dec.raw_decode(raw, i)
            i = j
            v = obj.get('values', {})
            mp = '>'.join(s.strip() for s in v.get('menuPath', '').split('>')) if v.get('menuPath') else ''  # MenuPath trims (MenuPath.java#L66-L80)
            menu = v.get('menu')
            if menu:
                mp = '>'.join(e.get('label', '') for e in menu)  # @Menu labels are not trimmed (PluginInfo.java#L533-L541)
                acc = [e.get('accelerator') for e in menu if e.get('accelerator')]
            else:
                acc = []
            attrs = v.get('attrs') or []
            sj_all.append(dict(jar=rel, cls=obj.get('class'), type=v.get('type', ''),
                               menuPath=mp, menuRoot=v.get('menuRoot', ''), label=v.get('label', ''),
                               name=v.get('name', ''), priority=v.get('priority', ''),
                               headless=v.get('headless', ''), visible=v.get('visible', ''),
                               accelerator=';'.join(acc),
                               attrs=';'.join('%s=%s' % (a.get('name'), a.get('value', '')) for a in attrs),
                               iconPath=v.get('iconPath', '')))
    for n in names:
        if n.startswith('scripts/') and not n.endswith('/'):
            jar_scripts.append((rel, n))
    z.close()

# ---------------------------------------------------------------- 6. SciJava rows (Command-like and tools; all others to aux csv)
with open(os.path.join(OUT, 'scijava_index_all.csv'), 'w', newline='', encoding='utf-8') as f:
    w = csv.DictWriter(f, fieldnames=list(sj_all[0].keys()))
    w.writeheader()
    for d in sj_all:
        w.writerow(d)
CMDLIKE = ('org.scijava.command.Command', 'org.scijava.tool.Tool')
for d in sj_all:
    if d['type'] in CMDLIKE or d['menuPath']:
        mp = d['menuPath']
        menu, lab = (mp.rsplit('>', 1) if '>' in mp else (mp, d['label'] or mp))
        notes = ['type=' + d['type']]
        if d['priority'] != '':
            notes.append('priority=%s' % d['priority'])
        if d['headless'] != '':
            notes.append('headless=%s' % str(d['headless']).lower())
        if d['visible'] != '':
            notes.append('visible=%s' % str(d['visible']).lower())
        if d['menuRoot']:
            notes.append('menuRoot=' + d['menuRoot'])
        if d['attrs']:
            notes.append('attrs=' + d['attrs'])
        if d['name']:
            notes.append('name=' + d['name'])
        add(command_id='ij2:' + d['cls'], label=lab or d['label'], menu_path=menu,
            shortcut=d['accelerator'], framework='IJ2', implementation=d['cls'],
            source_artifact='Fiji/%s!/META-INF/json/org.scijava.plugin.Plugin' % d['jar'],
            source_kind='scijava-index', notes='; '.join(notes))

# ---------------------------------------------------------------- 7. SciJava script dirs (scripts/, plugins/Scripts, plugins/ prefix Plugins) + jar scripts/
SJ_EXT = {'.ijm', '.js', '.py', '.groovy', '.bsh', '.rb', '.clj', '.scala', '.java', '.r', '.R', '.txt', '.m', '.kt'}


def script_menu(relpath, prefix):
    base = relpath.rsplit('.', 1)[0] if '.' in relpath.split('/')[-1] else relpath
    parts = ([prefix] if prefix else []) + base.replace('_', ' ').split('/')
    if len(parts) == 1:
        parts = ['Plugins', 'Scripts'] + parts
    return parts


for root, prefix, kind in ((os.path.join(FIJI, 'scripts'), None, 'scripts-dir'),
                           (os.path.join(FIJI, 'plugins'), 'Plugins', 'plugins-dir-script')):
    for dp, dn, fn in os.walk(root):
        for f in fn:
            ext = os.path.splitext(f)[1]
            if f.endswith(('.jar', '.class')) or ext not in SJ_EXT:
                continue
            relp = os.path.relpath(os.path.join(dp, f), root).replace('\\', '/')
            parts = script_menu(relp, prefix)
            sid = 'script:%s/%s' % ('scripts' if prefix is None else 'plugins', relp)
            add(command_id=sid, label=parts[-1], menu_path='>'.join(parts[:-1]), framework='script',
                implementation=('scripts/' if prefix is None else 'plugins/') + relp, arg='',
                source_artifact='Fiji/' + ('scripts/' if prefix is None else 'plugins/') + relp,
                source_kind=kind,
                notes='SciJava ScriptFinder (scijava-common ScriptFinder#L159-L205); registered only if a ScriptLanguage handles extension ' + ext)
for rel, n in jar_scripts:
    relp = n[len('scripts/'):]
    ext = os.path.splitext(n)[1]
    parts = script_menu(relp, None)
    add(command_id='script:%s!/%s' % (rel, n), label=parts[-1], menu_path='>'.join(parts[:-1]),
        framework='script', implementation=n, source_artifact='Fiji/%s!/%s' % (rel, n),
        source_kind='jar-scripts-resource', notes='classpath resource under scripts/ (ScriptFinder.scanResources); ext ' + ext)

# ---------------------------------------------------------------- 8. StartupMacros.fiji.ijm and toolsets
sm = os.path.join(FIJI, 'macros', 'StartupMacros.fiji.ijm')
for ln, line in enumerate(open(sm, encoding='utf-8', errors='replace').read().splitlines(), 1):
    m = re.match(r'\s*macro\s*"([^"]*)"', line)
    if not m:
        continue
    name = m.group(1)
    is_tool = '-' in name and ('Tool' in name or 'tool' in name)  # MacroInstaller.java#L102
    if name.startswith('AutoRun'):
        kind, menu, lab = 'autorun', '(startup)', name
    elif name == 'Popup Menu':
        kind, menu, lab = 'popup-menu', 'Popup', name
    elif is_tool:
        lab = name.split(' - ')[0].strip() if ' - ' in name else name.split('-')[0].strip()
        kind, menu = ('action tool' if 'Action Tool' in name else 'menu tool' if 'Menu Tool' in name else 'tool'), 'Toolbar'
    elif name == '-':
        continue
    else:
        kind, menu, lab = 'macro', 'Plugins>Macros', name
    sc = ''
    mm = re.search(r'\[(.+)\]\s*$', lab)
    if mm:
        sc = 'macro-key[%s]' % mm.group(1)
    add(command_id='macro-tool:StartupMacros.fiji.ijm#%s' % lab, label=lab, menu_path=menu, shortcut=sc,
        framework='macro-tool', implementation='macros/StartupMacros.fiji.ijm', arg=name,
        source_artifact='Fiji/macros/StartupMacros.fiji.ijm#L%d' % ln, source_kind='startup-macros',
        notes=kind + '; installed by ij.plugin.MacroInstaller (ij1-patcher overrideStartupMacrosForFiji selects .fiji.ijm)')
for f in sorted(os.listdir(os.path.join(FIJI, 'macros', 'toolsets'))):
    add(command_id='macro-tool:toolsets/%s' % f, label=f.rsplit('.', 1)[0], menu_path='Toolbar>More Tools (>>)',
        framework='macro-tool', implementation='macros/toolsets/' + f, source_artifact='Fiji/macros/toolsets/' + f,
        source_kind='startup-macros', notes='toolset selectable from toolbar ">>" menu (Toolbar.java)')

# ---------------------------------------------------------------- 9. Toolbar built-in tools
tb = open(os.path.join(IJSRC, 'ij', 'gui', 'Toolbar.java'), encoding='utf-8').read().splitlines()
for ln in range(24, 39):
    m = re.search(r'public static final int (\w+) = (\d+)', tb[ln - 1])
    if m and m.group(1) != 'UNUSED':
        add(command_id='ij1tool:%s' % m.group(1), label=m.group(1).title(), menu_path='Toolbar',
            framework='IJ1-tool', implementation='ij.gui.Toolbar', arg=m.group(2),
            source_artifact='imagej/ImageJ@v1.54p:ij/gui/Toolbar.java#L%d' % ln, source_kind='Toolbar.java',
            notes='built-in tool id %s; IJ.setTool / macro setTool(); interactive only' % m.group(2))

# ---------------------------------------------------------------- output
json.dump(rows, open(os.path.join(OUT, 'static_rows.json'), 'w', encoding='utf-8'), indent=0)
with open(os.path.join(OUT, 'plugins_config_raw.csv'), 'w', newline='', encoding='utf-8') as f:
    w = csv.writer(f)
    w.writerow(['jar', 'entry', 'line', 'text'])
    w.writerows(pc_raw)
with open(os.path.join(OUT, 'P10-summary.txt'), 'w', encoding='utf-8') as f:
    f.write('jars scanned: %d\n' % len(all_jars))
    f.write('IJ_Props section counts: %s\n' % dict(props_sections))
    f.write('IJ_Props non-menu keys: %s\n' % [(a, b) for a, b, c in other_props])
    f.write('rows by (framework, source_kind): %s\n' % dict(Counter((r['framework'], r['source_kind']) for r in rows)))
    f.write('jars with plugins.config: %d\n' % len({r[0] for r in pc_raw}))
    f.write('plugins.config lines: %d\n' % len(pc_raw))
    f.write('auto-generated config jars: %s\n' % sorted(set(autogen)))
    f.write('scijava index entries: %d in %d jars\n' % (len(sj_all), len({d['jar'] for d in sj_all})))
    f.write('scijava by type: %s\n' % dict(Counter(d['type'] for d in sj_all).most_common()))
    f.write('scijava with menuPath: %d\n' % sum(1 for d in sj_all if d['menuPath']))
    f.write('jar scripts/ resources: %d\n' % len(jar_scripts))
    for l in log:
        f.write('LOG ' + l + '\n')
print(open(os.path.join(OUT, 'P10-summary.txt'), encoding='utf-8').read())
