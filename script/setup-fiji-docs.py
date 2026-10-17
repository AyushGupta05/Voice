#!/usr/bin/env python3
"""Download the official ImageJ/Fiji documentation into .local/upstream/imagej-docs (idempotent, pinned).

Sources (all official, read-only, no paid services):
  * imagej/imagej.github.io — the imagej.net wiki (Fiji/ImageJ2 docs, plugin pages, scripting guides) as Markdown,
    sparse-checked out (_pages only) at the commit pinned in script/dependencies.json; images and other
    non-documentation assets are pruned.
  * ImageJ 1.x User Guide (IJ 1.46r, Tiago Ferreira) — HTML pages converted to Markdown.
  * ImageJ 1.x menu reference (imagej.net/ij/docs/menus) — exact command names and options.
  * ImageJ built-in macro function reference (imagej.net/ij/developer/macro/functions.html).

Downloaded pages are verified against SHA-256 pins. Run with --update-pins to (re)record the pins after an
intentional refresh. Prints the Markdown document count at the end so the gateway's knowledge count can be checked.
"""
import argparse
import hashlib
import html
import json
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.request
from html.parser import HTMLParser

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'
MANIFEST_PATH = ROOT / 'script/dependencies.json'
DOCS = LOCAL / 'upstream/imagej-docs'
DOWNLOADS = LOCAL / 'downloads/imagej-docs'
KEY = 'imagej-docs'

WIKI_REPOSITORY = 'https://github.com/imagej/imagej.github.io.git'
WIKI_DEFAULT_COMMIT = 'f6b0a55580008deae7af5a7aa36e94ca487ea45b'  # main, 17 Oct 2026
GUIDE_BASE = 'https://imagej.net/ij/docs/guide/'
GUIDE_PAGES = ['146', '146-Part-I', '146-2', '146-3', '146-4', '146-5', '146-7', '146-8', '146-9', '146-10', '146-11',
               '146-13', '146-14', '146-Part-III', '146-17', '146-18', '146-19', '146-21', '146-23', '146-24', '146-25',
               '146-Part-IV', '146-26', '146-27', '146-28', '146-29', '146-30', '146-Part-V', '146-33', '146-35',
               '146-References', '146-Section--1', '146-Section--2']
MENU_PAGES = ['file', 'edit', 'image', 'process', 'analyze', 'plugins', 'window', 'help']
MENU_BASE = 'https://imagej.net/ij/docs/menus/'
MACRO_FUNCTIONS_URL = 'https://imagej.net/ij/developer/macro/functions.html'


def run(*args, cwd=None, capture=False):
    return subprocess.run(args, cwd=str(cwd or ROOT), check=True, text=True, capture_output=capture)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def load_manifest():
    return json.loads(MANIFEST_PATH.read_text())


def save_manifest(manifest):
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + '\n')


class TextExtractor(HTMLParser):
    """Minimal HTML → Markdown-ish text: headings, paragraphs, lists, code blocks; scripts/styles dropped."""
    BLOCK = {'p', 'div', 'br', 'li', 'tr', 'blockquote', 'table', 'ul', 'ol', 'dd', 'dt', 'hr'}
    SKIP = {'script', 'style', 'head', 'noscript', 'iframe'}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.skip = 0
        self.pre = 0
        self.heading = None

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
            return
        if self.skip:
            return
        if tag in ('h1', 'h2', 'h3', 'h4', 'h5', 'h6'):
            self.parts.append('\n\n' + '#' * int(tag[1]) + ' ')
            self.heading = tag
        elif tag == 'pre':
            self.parts.append('\n\n```\n')
            self.pre += 1
        elif tag == 'li':
            self.parts.append('\n- ')
        elif tag in ('td', 'th'):
            self.parts.append(' | ')
        elif tag in self.BLOCK:
            self.parts.append('\n\n' if tag != 'br' else '\n')
        elif tag in ('b', 'strong') and not self.pre:
            self.parts.append('**')

    def handle_endtag(self, tag):
        if tag in self.SKIP:
            self.skip = max(0, self.skip - 1)
            return
        if self.skip:
            return
        if tag in ('h1', 'h2', 'h3', 'h4', 'h5', 'h6'):
            self.parts.append('\n\n')
            self.heading = None
        elif tag == 'pre':
            self.parts.append('\n```\n\n')
            self.pre = max(0, self.pre - 1)
        elif tag in self.BLOCK:
            self.parts.append('\n')
        elif tag in ('b', 'strong') and not self.pre:
            self.parts.append('**')

    def handle_data(self, data):
        if self.skip:
            return
        if self.pre:
            self.parts.append(data)
        else:
            text = re.sub(r'\s+', ' ', data)
            if text.strip() or (self.parts and not self.parts[-1].endswith(('\n', ' '))):
                self.parts.append(text)

    def text(self):
        out = ''.join(self.parts)
        out = out.replace('**', '', 0) if False else out
        out = re.sub(r'\*\*\s*\*\*', '', out)
        out = re.sub(r'[ \t]+\n', '\n', out)
        out = re.sub(r'\n{3,}', '\n\n', out)
        return out.strip() + '\n'


def html_to_markdown(source: str, title: str, url: str):
    extractor = TextExtractor()
    extractor.feed(source)
    body = extractor.text()
    header = f'# {title}\n\nSource: {url} (official ImageJ documentation, converted to text by script/setup-fiji-docs.py).\n\n'
    if body.startswith('# '):
        return header + body
    return header + body


def page_title(source: str, fallback: str):
    match = re.search(r'<title>(.*?)</title>', source, re.S | re.I)
    if match:
        return html.unescape(re.sub(r'\s+', ' ', match.group(1))).strip() or fallback
    return fallback


def fetch(url: str, target: pathlib.Path, expected: str | None, update_pins: bool):
    """Download to target (idempotent), verify pin; returns the sha256."""
    if target.exists() and (expected is None or digest(target) == expected or update_pins):
        return digest(target)
    partial = target.with_suffix(target.suffix + '.part')
    print('Downloading ' + url, flush=True)
    request = urllib.request.Request(url, headers={'User-Agent': 'luma-setup-fiji-docs/1.0'})
    with urllib.request.urlopen(request, timeout=90) as response, partial.open('wb') as destination:
        shutil.copyfileobj(response, destination)
    actual = digest(partial)
    if expected is not None and not update_pins and actual != expected:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f'Hash mismatch for {url}: expected {expected}, got {actual}. '
                           'The upstream page changed; rerun with --update-pins after reviewing it.')
    partial.replace(target)
    return actual


def setup_wiki(pins: dict, update_pins: bool):
    commit = pins.get('wiki', {}).get('commit', WIKI_DEFAULT_COMMIT)
    repository = pins.get('wiki', {}).get('repository', WIKI_REPOSITORY)
    directory = DOCS / 'imagej.github.io'
    if directory.exists() and (directory / '.git').exists():
        head = run('git', 'rev-parse', 'HEAD', cwd=directory, capture=True).stdout.strip()
        if head == commit:
            print('Preserving existing checkout ' + str(directory.relative_to(ROOT)) + ' at ' + commit[:12], flush=True)
        else:
            print(f'Updating {directory.relative_to(ROOT)} from {head[:12]} to {commit[:12]}', flush=True)
            run('git', 'fetch', '--depth', '1', '--filter=blob:none', 'origin', commit, cwd=directory)
            run('git', 'checkout', '--detach', '-q', commit, cwd=directory)
    else:
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
        run('git', 'init', '-q', cwd=directory)
        run('git', 'remote', 'add', 'origin', repository, cwd=directory)
        run('git', 'sparse-checkout', 'set', '_pages', cwd=directory)
        print(f'Fetching {repository} at {commit[:12]} (sparse: _pages)', flush=True)
        run('git', 'fetch', '-q', '--depth', '1', '--filter=blob:none', 'origin', commit, cwd=directory)
        run('git', 'checkout', '--detach', '-q', commit, cwd=directory)
    pruned = 0
    for path in directory.rglob('*'):
        if '.git' in path.parts or not path.is_file():
            continue
        if path.suffix.lower() not in ('.md', '.txt') or path.name in ('missing-files.txt', 'missing-images.txt'):
            path.unlink()
            pruned += 1
    for path in sorted(directory.rglob('*'), key=lambda p: -len(p.parts)):
        if path.is_dir() and '.git' not in path.parts and not any(path.iterdir()):
            path.rmdir()
    if pruned:
        print(f'Pruned {pruned} non-documentation files from the wiki checkout', flush=True)
    pins['wiki'] = {'repository': repository, 'commit': commit}
    return commit


def setup_pages(pins: dict, update_pins: bool):
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    pages = pins.setdefault('pages', {})
    plan = []
    for name in GUIDE_PAGES:
        plan.append((f'user-guide/{name}.md', GUIDE_BASE + name + '.html'))
    for name in MENU_PAGES:
        plan.append((f'menus/{name}.md', MENU_BASE + name + '.html'))
    plan.append(('macro-functions.md', MACRO_FUNCTIONS_URL))
    converted = 0
    for relative, url in plan:
        cached = DOWNLOADS / (relative.replace('/', '__')[:-3] + '.html')
        expected = pages.get(relative, {}).get('sha256')
        sha = fetch(url, cached, expected, update_pins)
        pages[relative] = {'url': url, 'sha256': sha}
        target = DOCS / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        source = cached.read_text('utf8', errors='replace')
        fallback = pathlib.Path(relative).stem
        markdown = html_to_markdown(source, page_title(source, fallback), url)
        if not target.exists() or target.read_text('utf8') != markdown:
            target.write_text(markdown, 'utf8')
            converted += 1
    print(f'Converted {converted} HTML pages to Markdown ({len(plan)} total)', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--update-pins', action='store_true', help='record new SHA-256 pins for downloaded pages')
    args = parser.parse_args()
    if not shutil.which('git'):
        raise RuntimeError('Missing prerequisite: git')
    manifest = load_manifest()
    pins = manifest.get(KEY, {})
    DOCS.mkdir(parents=True, exist_ok=True)
    setup_wiki(pins, args.update_pins)
    setup_pages(pins, args.update_pins)
    manifest[KEY] = pins
    if args.update_pins or KEY not in load_manifest():
        save_manifest(manifest)
        print('Recorded pins in script/dependencies.json', flush=True)
    count = sum(1 for p in DOCS.rglob('*.md') if '.git' not in p.parts)
    wiki = sum(1 for p in (DOCS / 'imagej.github.io').rglob('*.md') if '.git' not in p.parts)
    print(f'ImageJ documentation ready: {count} Markdown documents in {DOCS.relative_to(ROOT)} '
          f'({wiki} wiki pages, {count - wiki} converted ImageJ 1.x pages)', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print('setup-fiji-docs: ' + str(error), file=sys.stderr)
        sys.exit(1)
