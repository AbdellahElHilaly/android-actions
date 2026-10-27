#!/usr/bin/env python3
"""Stage a native Gradle Android project, or turn HTML/ZIP into a WebView Android project."""
from __future__ import annotations

import argparse
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
from xml.sax.saxutils import escape
import zipfile

REPO_ROOT = Path(__file__).resolve().parents[1]
WORK_ROOT = REPO_ROOT / '.factory-work'
MAX_FILE = 60 * 1024 * 1024
MAX_TOTAL = 300 * 1024 * 1024
MAX_FILES = 3000


def safe_repo_path(root: Path, provided: str, kind='path') -> Path:
    if not provided or Path(provided).is_absolute():
        raise ValueError(f'{kind} must be a relative path within the source repository')
    base = root.resolve()
    dest = (base / provided).resolve()
    if not dest.is_relative_to(base) or not dest.exists():
        raise ValueError(f'{kind} missing or escapes its source root: {provided}')
    return dest


def extract_site_zip(archive: Path, dest: Path):
    dest.mkdir(parents=True, exist_ok=True)
    total = 0
    with zipfile.ZipFile(archive) as z:
        infos = z.infolist()
        if len(infos) > MAX_FILES:
            raise ValueError('ZIP contains too many files')
        for info in infos:
            p = PurePosixPath(info.filename.replace('\\', '/'))
            if not info.filename or p.is_absolute() or '..' in p.parts or '\0' in info.filename:
                raise ValueError(f'Unsafe ZIP member: {info.filename}')
            if info.filename.startswith('\\') or re.match(r'^[A-Za-z]:', info.filename):
                raise ValueError(f'Unsafe ZIP member: {info.filename}')
            mode = (info.external_attr >> 16) & 0o170000
            if mode in (0o120000, 0o060000, 0o020000):
                raise ValueError(f'Unsafe ZIP member type: {info.filename}')
            if info.is_dir():
                continue
            if info.file_size > MAX_FILE:
                raise ValueError(f'ZIP member too large: {info.filename}')
            total += info.file_size
            if total > MAX_TOTAL:
                raise ValueError('ZIP uncompressed size limit exceeded')
            target = (dest / str(p)).resolve()
            if not target.is_relative_to(dest.resolve()):
                raise ValueError('ZIP member escapes extraction directory')
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, 'wb') as out:
                shutil.copyfileobj(src, out, length=64 * 1024)
    return dest


def flatten_site(root: Path):
    matches = [x for x in root.rglob('*') if x.is_file() and x.name.lower() == 'index.html']
    if not matches:
        raise ValueError('HTML site needs index.html')
    if len(matches) > 1:
        # Most ZIPs contain a single enclosing folder; otherwise require a clear site root.
        root_index = next((x for x in matches if x.parent == root), None)
        if root_index is None:
            raise ValueError('Multiple index.html entries; select a site containing one root index')
        return root_index.parent
    return matches[0].parent


def copy_site(source: Path, dest: Path):
    if source.is_dir() and any(p.is_symlink() for p in source.rglob('*')):
        raise ValueError('Symlinks are not accepted inside local HTML folders')
    with tempfile.TemporaryDirectory(prefix='android-factory-') as temp:
        intermediate = Path(temp) / 'unpacked'
        if source.is_file() and source.suffix.lower() == '.zip':
            extract_site_zip(source, intermediate)
            site_dir = flatten_site(intermediate)
        elif source.is_dir():
            site_dir = flatten_site(source)
        else:
            raise ValueError('Site source must be a directory or ZIP')
        shutil.copytree(site_dir, dest, ignore=shutil.ignore_patterns('.git', '__pycache__', '.DS_Store'))
    if not (dest / 'index.html').is_file():
        raise ValueError('Site staging did not contain index.html')


def clone_public(repo: str, ref: str, dest: Path):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('GitHub repo must be owner/repository (public repositories only)')
    if not re.fullmatch(r'[A-Za-z0-9_.\-/]+', ref) or '..' in ref or ref.startswith('-'):
        raise ValueError('Invalid Git ref')
    subprocess.run(['git', 'clone', '--depth', '1', '--branch', ref,
                    f'https://github.com/{repo}.git', str(dest)], check=True)


def escape_android_string(value: str) -> str:
    value = value.replace('\\', '\\\\').replace("'", "\\'").replace('\"', '\\\"')
    value = value.replace('\n', '\\n').replace('\r', ' ')
    return escape(value)


def prepare(args):
    if WORK_ROOT.exists():
        shutil.rmtree(WORK_ROOT)
    WORK_ROOT.mkdir(parents=True)
    cloned = WORK_ROOT / 'source'
    if args.source == 'github':
        clone_public(args.repository, args.ref, cloned)
        source_root = cloned
    else:
        source_root = REPO_ROOT

    build_dir = WORK_ROOT / 'project'
    if args.mode == 'native':
        project = safe_repo_path(source_root, args.project_path, 'project_path')
        if not project.is_dir() or not (project / 'settings.gradle').exists() and not (project / 'settings.gradle.kts').exists():
            raise ValueError('Not a Gradle Android project: settings.gradle(.kts) is required')
        shutil.copytree(project, build_dir, ignore=shutil.ignore_patterns('.git', '.gradle', '.factory-work', 'build', '.idea', 'local.properties'))
    else:
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', args.application_id):
            raise ValueError('application-id must be an Android package identifier, e.g. com.example.app')
        if not args.app_name.strip() or len(args.app_name) > 50:
            raise ValueError('app-name must contain 1 to 50 characters')
        site = safe_repo_path(source_root, args.site_path, 'site_path')
        shutil.copytree(REPO_ROOT / 'projects' / 'web-shell', build_dir)
        dest = build_dir / 'app/src/main/assets/site'
        copy_site(site, dest)
        values = build_dir / 'app/src/main/res/values/strings.xml'
        values.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                          f'  <string name="app_name">{escape_android_string(args.app_name.strip())}</string>\n'
                          '</resources>\n', encoding='utf-8')
        prop = build_dir / 'factory.properties'
        prop.write_text('factoryApplicationId=' + args.application_id + '\n', encoding='utf-8')

    print(f'Build root: {build_dir}')
    return build_dir


def main(argv=None):
    parser = argparse.ArgumentParser(description='Stage native Android or HTML/ZIP project for APK build')
    parser.add_argument('--mode', choices=['native', 'web'], default='native')
    parser.add_argument('--source', choices=['local', 'github'], default='local')
    parser.add_argument('--repository', default='')
    parser.add_argument('--ref', default='main')
    parser.add_argument('--project-path', default='projects/html-shelf')
    parser.add_argument('--site-path', default='sites/demo')
    parser.add_argument('--app-name', default='My Web App')
    parser.add_argument('--application-id', default='com.androidfactory.webapp')
    args = parser.parse_args(argv)
    try:
        prepare(args)
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
