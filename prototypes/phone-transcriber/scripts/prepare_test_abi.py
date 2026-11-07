#!/usr/bin/env python3
"""CI-only: preserve APIs referenced by compiled instrumentation, not the whole app.

R8 optimizes the application without seeing the separately compiled test APK.
Read classfile constant pools (including descriptor-only constructor references)
to declare those external entry points before optimizing the application.
"""
import argparse
import io
from pathlib import Path
import re
import struct
import zipfile


def references(data: bytes) -> set[str]:
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Expected a JVM classfile')
    count = struct.unpack_from('>H', data, 8)[0]
    offset, index = 10, 1
    strings = []
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            strings.append(data[offset:offset + length].decode('utf-8', errors='replace'))
            offset += length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise ValueError(f'Unknown classfile constant tag: {tag}')
        index += 1
    names = set()
    for text in strings:
        if re.fullmatch(r'[\w$]+(?:/[\w$]+)+', text):
            names.add(text)
        names.update(re.findall(r'L([\w$/]+);', text))
    return {name.replace('/', '.') for name in names
            if name.startswith(('com/pmixay/ascent/', 'androidx/', 'kotlin/',
                                'kotlinx/', 'com/google/common/', 'ai/onnxruntime/',
                                'com/k2fsa/'))}


def archive_references(data: bytes) -> set[str]:
    names = set()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            if name.endswith('.class'):
                names.update(references(archive.read(name)))
            elif name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                names.update(archive_references(archive.read(name)))
    return names


def prepare(root: Path, output: Path, dependencies: Path | None = None) -> None:
    files = sorted(root.rglob('*.class'))
    if not files:
        raise ValueError('No compiled instrumentation classes; cannot establish test ABI')
    names = set().union(*(references(path.read_bytes()) for path in files))
    archives = []
    if dependencies is not None:
        archives = sorted(path for path in dependencies.iterdir() if path.suffix in ('.jar', '.aar'))
        if not archives:
            raise ValueError('No test-only dependency archives; cannot establish library ABI')
        for path in archives:
            names.update(archive_references(path.read_bytes()))
    if not names:
        raise ValueError('No instrumentation API references discovered')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('# Generated from compiled instrumentation entry points.\n' +
                      '\n'.join(f'-keep,includedescriptorclasses class {name} {{ *; }}'
                                for name in sorted(names)) + '\n')
    print(f'Instrumentation ABI: {len(files)} compiled classes, {len(archives)} test-only archives, {len(names)} referenced types')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--dependencies', type=Path)
    args = parser.parse_args()
    prepare(args.classes, args.output, args.dependencies)
