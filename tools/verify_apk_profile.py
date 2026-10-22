#!/usr/bin/env python3
"""Inspect compiled APK Manifest, every packaged scope configuration and DEX strings.

Requires Android SDK aapt2 and apkanalyzer. No third-party Python dependencies.
This proves artifact structure, not successful hook execution on a phone.
"""
import argparse
import pathlib
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ANDROID = '{http://schemas.android.com/apk/res/android}'
MINIMAL = {'com.samsung.android.kmxservice', 'com.osp.app.signin',
           'com.samsung.android.app.sharelive', 'com.sec.android.app.shealth'}
WA = {'com.whatsapp', 'com.whatsapp.w4b', 'com.waenhancer.community'}
SYSTEM = {'com.android.server.knox.dar.DarManagerService',
          'com.samsung.android.knoxguard.service.KnoxGuardService',
          'com.samsung.android.knoxguard.service.KnoxGuardSeService'}


def scope_configs(dump, resource_id=None):
    # AAPT resource optimization can rename array/scope. Resolve the exact ID
    # referenced by the compiled Manifest, rather than trusting a source name.
    blocks = re.findall(r'(?ms)^\s*resource\s+(0x[0-9a-fA-F]+)\s+(.+?)(?=^\s*resource\s+0x|\Z)', dump)
    if resource_id is not None:
        scopes = [b for rid, b in blocks if int(rid, 16) == resource_id]
    else:
        scopes = [b for rid, b in blocks if re.match(r'(?:\S+:)?array/scope(?:\s|$)', b)]
    if len(scopes) != 1:
        raise AssertionError(f'Expected one compiled scope array, id={resource_id!r}')
    configs = []
    for part in re.split(r'(?m)^\s*\([^\n]*\)\s+\(array\)', scopes[0])[1:]:
        # aapt2 prints array string values in quotes, including qualified arrays.
        items = re.findall(r'"([^"\n]+)"', part)
        if not items:
            raise AssertionError('Scope array has no decoded strings: ' + part)
        if len(items) != len(set(items)):
            raise AssertionError('Duplicate compiled scope')
        configs.append(set(items))
    if not configs:
        raise AssertionError('No compiled scope configurations decoded')
    return configs


def dex_strings(data):
    if data[:4] != b'dex\n':
        raise AssertionError('Unsupported DEX container')
    size, offset = struct.unpack_from('<II', data, 56)
    result = set()
    for i in range(size):
        pos = struct.unpack_from('<I', data, offset + 4*i)[0]
        while data[pos] & 0x80:
            pos += 1
        pos += 1
        end = data.index(b'\0', pos)
        result.add(data[pos:end].decode('utf-8', errors='replace'))
    return result


def verify(apk, profile, aapt2, apkanalyzer):
    xml = subprocess.check_output([apkanalyzer, 'manifest', 'print', str(apk)], text=True)
    manifest = ET.fromstring(xml)
    module = 'com.waenhancer.community' if profile == 'AppOnly' else 'io.mesalabs.knoxpatch'
    assert manifest.get('package') == module
    app = manifest.find('application')
    metadata = {e.get(ANDROID+'name'): e for e in app.findall('meta-data')}
    key = ('com.waenhancer.HOOK_PROFILE' if profile == 'AppOnly'
           else 'io.mesalabs.knoxpatch.HOOK_PROFILE')
    assert metadata[key].get(ANDROID+'value') == profile
    scope_ref = metadata['xposedscope'].get(ANDROID+'resource', '')
    print(f'Compiled {profile} scope reference: {scope_ref!r}', flush=True)
    resource_id = None
    if scope_ref != '@array/scope':
        match = re.fullmatch(r'@(?:ref/)?(?:0x)?([0-9a-fA-F]{8})', scope_ref)
        if not match:
            raise AssertionError(f'Unsupported compiled scope reference: {scope_ref!r}')
        resource_id = int(match[1], 16)
    dump = subprocess.check_output([aapt2, 'dump', 'resources', '--values', str(apk)], text=True)
    configs = scope_configs(dump, resource_id)
    with zipfile.ZipFile(apk) as archive:
        strings = set()
        dex_files = [n for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
        assert dex_files
        for name in dex_files:
            strings.update(dex_strings(archive.read(name)))
        modern = 'META-INF/xposed/scope.list'
        if modern in archive.namelist():
            actual = set(archive.read(modern).decode().splitlines())
            assert actual == (WA if profile == 'AppOnly' else MINIMAL if profile == 'Minimal' else configs[-1])
        assert archive.read('assets/xposed_init').strip(), 'Missing legacy Xposed entrypoint'
    if profile == 'Minimal':
        assert all(c == MINIMAL for c in configs), configs
        assert not SYSTEM.intersection(strings), 'Minimal includes system hook implementations'
    elif profile == 'Full':
        assert all('android' in c and len(c) > 4 for c in configs), configs
        assert SYSTEM <= strings, 'Full lost system hook implementations'
    else:
        assert all(c == WA for c in configs), configs
        forbidden = {'com.android.server.pm.AppsFilterBase', 'shouldFilterApplication',
                     'com.android.providers.settings.SettingsProvider', 'checkDowngrade',
                     'debug.waenhancer.lsposed', 'debug.waenhancer.lsposed.api'}
        assert not forbidden.intersection(strings), forbidden.intersection(strings)
        assert 'getHookBinder' in strings
        assert not any('BridgeService' in e.get(ANDROID+'name','') for e in app.findall('service'))
    if profile != 'AppOnly':
        libraries = {e.get(ANDROID+'name') for e in app.findall('uses-library')}
        assert 'com.samsung.device' in libraries and 'com.samsung.device.lite' not in libraries
    print(f'{apk}: {profile} manifest, {len(configs)} scope configurations and {len(dex_files)} DEX files verified')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('apk', type=pathlib.Path)
    p.add_argument('profile', choices=['Full', 'Minimal', 'AppOnly'])
    p.add_argument('--aapt2', required=True)
    p.add_argument('--apkanalyzer', required=True)
    a = p.parse_args()
    verify(a.apk, a.profile, a.aapt2, a.apkanalyzer)
