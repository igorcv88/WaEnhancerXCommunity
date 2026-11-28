"""Independent string-anchor census of a host APK, without DexKit.

Every string literal that a resolver in Unobfuscator.java or HostDexContracts.java passes to a
string matcher is counted in the host DEX code with `dexdump -d` (Android build-tools). The
result says, per anchor, how many methods and declaring classes load a constant that equals or
contains it. That is L0 evidence: an anchor with no use cannot resolve, and one with several uses
needs the resolver's other filters to choose. It does not execute the resolvers; for that see
OfflineDexAudit (DexKit) and the runtime probe.

    python anchor_census.py census --dexdump <build-tools>/dexdump --apk base.apk --label 2.26.46.82
    python anchor_census.py check --fixture fixtures/anchor_census.json
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCES = [ROOT / "app/src/main/java/com/waenhancer/xposed/core/devkit/Unobfuscator.java",
           ROOT / "app/src/main/java/com/waenhancer/xposed/core/devkit/HostDexContracts.java"]
FIXTURE = HERE / "fixtures/anchor_census.json"
CALLS = re.compile(r"\b(addUsingString|usingStrings|usingEqStrings|find(?:First|All)(?:Method|Class)UsingStrings)\(")
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
CONSTANT = re.compile(r'(?:static\s+final\s+String\s+)(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*;')


def _unescape(text):
    return bytes(text, "utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8")


def _arguments(source, start):
    """Text of the call's argument list, honouring nested parentheses and string literals."""
    depth, i, quoted = 1, start, False
    while i < len(source) and depth:
        c = source[i]
        if quoted:
            if c == "\\":
                i += 1
            elif c == '"':
                quoted = False
        elif c == '"':
            quoted = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return source[start:i - 1]


def strip_comments(source):
    out, i, quoted = [], 0, False
    while i < len(source):
        if quoted:
            out.append(source[i])
            if source[i] == "\\" and i + 1 < len(source):
                out.append(source[i + 1])
                i += 1
            elif source[i] == '"':
                quoted = False
        elif source.startswith("//", i):
            while i < len(source) and source[i] != "\n":
                i += 1
            continue
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = len(source) if end < 0 else end + 2
            continue
        else:
            if source[i] == '"':
                quoted = True
            out.append(source[i])
        i += 1
    return "".join(out)


def anchors(sources=None):
    """String literals and String constants passed to string matchers, sorted."""
    found = set()
    for path in sources or SOURCES:
        code = strip_comments(path.read_text(encoding="utf-8"))
        constants = {name: _unescape(value) for name, value in CONSTANT.findall(code)}
        for call in CALLS.finditer(code):
            args = _arguments(code, call.end())
            for literal in LITERAL.findall(args):
                found.add(_unescape(literal))
            without = LITERAL.sub("", args)
            for name in re.findall(r"\b[A-Z][A-Z0-9_]+\b", without):
                if name in constants:
                    found.add(constants[name])
    return sorted(a for a in found if a)


def parse_dexdump(lines):
    """Yields (class descriptor, method key, constant) for every const-string in dexdump -d."""
    cls = meth = sig = None
    for line in lines:
        if line.startswith("  Class descriptor"):
            cls = line.split("'")[1]
        elif line.startswith("      name          :"):
            meth = line.split("'")[1]
        elif line.startswith("      type          :"):
            sig = line.split("'")[1]
        elif "const-string" in line and cls:
            match = re.search(r'const-string(?:/jumbo)?\s+v\d+,\s+"(.*)" // string@', line)
            if match:
                yield cls, f"{cls}->{meth}{sig}", match.group(1)


def count(lines, wanted):
    """{anchor: {"equals": [methods, classes], "contains": [methods, classes]}}."""
    hits = {a: {"equals": (set(), set()), "contains": (set(), set())} for a in wanted}
    for cls, method, constant in parse_dexdump(lines):
        for anchor in wanted:
            if anchor in constant:
                kinds = ("contains", "equals") if constant == anchor else ("contains",)
                for kind in kinds:
                    hits[anchor][kind][0].add(method)
                    hits[anchor][kind][1].add(cls)
    return {a: {k: [len(m), len(c)] for k, (m, c) in v.items()} for a, v in hits.items()}


def classify(entry):
    methods = entry["contains"][0]
    return "ABSENT" if methods == 0 else "UNIQUE_METHOD" if methods == 1 else "MULTIPLE_METHODS"


def census(dexdump, apk, wanted):
    totals = {a: {"equals": [0, 0], "contains": [0, 0]} for a in wanted}
    with zipfile.ZipFile(apk) as zf, tempfile.TemporaryDirectory() as tmp:
        dexes = sorted((n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n)),
                       key=lambda n: int(re.sub(r"\D", "", n) or 1))
        for name in dexes:
            path = Path(tmp) / name
            path.write_bytes(zf.read(name))
            out = subprocess.run([str(dexdump), "-d", str(path)], check=True, stdout=subprocess.PIPE)
            # Classes never span DEX files, so per-file counts add up.
            part = count(out.stdout.decode("utf-8", "replace").splitlines(), wanted)
            for a in wanted:
                for k in ("equals", "contains"):
                    totals[a][k] = [x + y for x, y in zip(totals[a][k], part[a][k])]
    return totals


def check(fixture):
    """Every anchor in the sources must have a recorded census for every audited APK."""
    data = json.loads(fixture.read_text(encoding="utf-8"))
    current = set(anchors())
    problems = []
    for label, apk in data["apks"].items():
        missing = current - set(apk["anchors"])
        if missing:
            problems.append(f"{label}: no census for {sorted(missing)[:5]} ({len(missing)} total)")
    return problems


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("census")
    c.add_argument("--dexdump", required=True, type=Path)
    c.add_argument("--apk", required=True, type=Path)
    c.add_argument("--label", required=True)
    c.add_argument("--version-code", required=True, type=int)
    c.add_argument("--fixture", default=FIXTURE, type=Path)
    k = sub.add_parser("check")
    k.add_argument("--fixture", default=FIXTURE, type=Path)
    args = p.parse_args()
    if args.cmd == "check":
        problems = check(args.fixture)
        print("\n".join(problems) or "census covers every source anchor")
        return 1 if problems else 0
    wanted = anchors()
    result = census(args.dexdump, args.apk, wanted)
    data = (json.loads(args.fixture.read_text(encoding="utf-8")) if args.fixture.exists()
            else {"schema": 1, "apks": {}})
    data["apks"][args.label] = {"versionCode": args.version_code, "anchors": result}
    args.fixture.parent.mkdir(parents=True, exist_ok=True)
    args.fixture.write_text(json.dumps(data, indent=1, sort_keys=True, ensure_ascii=False) + "\n",
                            encoding="utf-8", newline="\n")
    absent = [a for a in wanted if classify(result[a]) == "ABSENT"]
    print(f"{args.label}: {len(wanted)} anchors, {len(absent)} absent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
