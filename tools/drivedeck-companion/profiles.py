"""Pinned binary adapters. Unknown upstream APKs fail closed before any patching."""
from dataclasses import dataclass
from pathlib import Path
import json
import re

@dataclass(frozen=True)
class Profile:
    original_sha256: str
    version: str
    code: int
    upstream_code: int
    patch: str
    symbols: dict

LEGACY = Profile('bb67e07ff14a6300082a33fa75be2082ab250e5aec1bd6f75e72b5b034b157c4', '0.6.8', 305, 300, 'patch.py', {
    'RENDERER_CLASS': 'c.Y7', 'OPEN_SPLIT': 'z', 'SET_HANDLE': 'o',
    'SCALE': 'L', 'WIDTH': 'H', 'HEIGHT': 'I', 'DENSITY': 'M',
    'CLOSED': 'A', 'RELEASING': 'B', 'ENABLED': 'c0', 'READY': 'S',
})
CURRENT = Profile('e32ac90ed66d7d325653ed475cd3623d41d7737aeb321813991aedab7b06a003', '0.6.9', 332, 331, 'patch_069.py', {
    'RENDERER_CLASS': 'c.g8', 'OPEN_SPLIT': 'A', 'SET_HANDLE': 'q',
    'SCALE': 'O', 'WIDTH': 'K', 'HEIGHT': 'L', 'DENSITY': 'P',
    'CLOSED': 'D', 'RELEASING': 'E', 'ENABLED': 'g0', 'READY': 'W',
})
PROFILES = {p.original_sha256: p for p in (LEGACY, CURRENT)}

def select(sha256: str) -> Profile:
    if sha256 not in PROFILES:
        raise ValueError('Unsupported original APK SHA-256: ' + sha256)
    return PROFILES[sha256]

def generate_symbols(profile: Profile, decoded: Path, destination: Path):
    """Validate the exact reflection contract against decoded declarations before compiling it."""
    smali = list((decoded / 'smali').rglob('*.smali'))
    class_name = 'L' + profile.symbols['RENDERER_CLASS'].replace('.', '/') + ';'
    candidates = [p.read_text() for p in smali if re.search(r'(?m)^\.class [^\n]+ ' + re.escape(class_name) + r'$', p.read_text())]
    assert len(candidates) == 1, 'Renderer declaration missing or ambiguous'
    renderer = candidates[0]
    for symbol, typ in {'SCALE':'I','WIDTH':'I','HEIGHT':'I','DENSITY':'I','CLOSED':'Z','RELEASING':'Z','ENABLED':'Z'}.items():
        assert re.search(r'(?m)^\.field public (?:final )?' + profile.symbols[symbol] + ':' + typ + '$', renderer), symbol
    assert re.search(r'(?m)^\.field public final ' + profile.symbols['READY'] + r':L[^;]+;$', renderer)
    assert re.search(r'(?m)^\.method public [^\n]*\b' + profile.symbols['SET_HANDLE'] + r'\(I\)V$', renderer)
    main = (decoded / 'smali/ru/drivedeck/launcher/MainActivity.smali').read_text()
    assert re.search(r'(?m)^\.method public final ' + profile.symbols['OPEN_SPLIT'] + r'\(\)V$', main)
    assert '.method public final i(I)V' in main
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text('package ru.drivedeck.headswap;\n/** Generated only for a verified upstream APK. */\nfinal class CompanionSymbols {\n' +
                           ''.join('    static final String ' + k + ' = ' + json.dumps(v) + ';\n' for k, v in profile.symbols.items()) + '}\n')
