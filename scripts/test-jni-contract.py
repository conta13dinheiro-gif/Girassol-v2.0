#!/usr/bin/env python3
"""Check every Kotlin external declaration against its C++ JNI signature.
Optionally verify those symbols in every ABI shipped in a universal APK.
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
types = dict(Unit='void', Int='jint', Long='jlong', Float='jfloat', Double='jdouble', Boolean='jboolean',
             String='jstring', ByteArray='jbyteArray', LongArray='jlongArray',
             FloatArray='jfloatArray', ByteBuffer='jobject')
expected = {}
for path in (root/'app/src/main/java').rglob('*.kt'):
    source = path.read_text()
    methods = list(re.finditer(r'external\s+fun\s+(\w+)\(([^)]*)\)(?:[ \t]*:[ \t]*(\w+))?', source, re.S))
    if not methods:
        continue
    package = re.search(r'^package ([\w.]+)', source, re.M).group(1)
    owner = re.search(r'^(?:internal\s+)?object\s+(\w+)', source, re.M).group(1)
    for method in methods:
        name, parameters, result = method.groups()
        params = [types[p.split(':')[1].strip().rstrip('?')] for p in parameters.split(',') if p.strip()]
        symbol = 'Java_' + package.replace('.', '_') + '_' + owner + '_' + name
        if symbol in expected:
            raise AssertionError('Overloaded native declaration needs explicit JNI mangling: '+symbol)
        expected[symbol] = (types[result or 'Unit'], params)

actual = {}
for path in (root/'app/src/main/cpp').rglob('*.cpp'):
    for match in re.finditer(r'JNIEXPORT\s+(\w+)\s+JNICALL\s+(Java_\w+)\s*\(([^)]*)\)', path.read_text(), re.S):
        result, symbol, args = match.groups()
        parameters = [re.match(r'\s*(\w+)', p).group(1) for p in args.split(',')][2:]
        if symbol in actual:
            raise AssertionError('Duplicate JNI implementation: '+symbol)
        actual[symbol] = (result, parameters)
assert expected, 'No native declarations discovered'
mismatches = {k: (v, actual.get(k)) for k, v in expected.items() if actual.get(k) != v}
assert expected == actual, f'JNI mismatch: {mismatches}; extra symbols: {actual.keys()-expected.keys()}'
print(f'{len(expected)} JNI declarations match C++ return/argument types')

if len(sys.argv) == 3:
    apk, nm = sys.argv[1:]
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temp:
        for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64'):
            symbols = set()
            for library in ('libgirassol_core.so', 'libgirassol_llama.so'):
                path = pathlib.Path(temp)/library
                path.write_bytes(archive.read(f'lib/{abi}/{library}'))
                output = subprocess.check_output([nm, '-D', '--defined-only', str(path)], text=True)
                symbols.update(line.split()[-1] for line in output.splitlines() if line.strip())
            assert expected.keys() <= symbols, f'{abi}: missing {expected.keys()-symbols}'
            print(f'{abi}: all {len(expected)} JNI symbols exported in APK')
elif len(sys.argv) != 1:
    raise SystemExit('usage: test-jni-contract.py [universal.apk llvm-nm]')
