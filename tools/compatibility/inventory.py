#!/usr/bin/env python3
"""Compare the checked-in host dependency inventory with executable source.

The scan covers literal call sites in all app source sets. It records dynamic arguments
as source expressions and does not infer what a reflection helper will select at runtime.
"""
import argparse
from collections import Counter
import difflib
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("dependencies.json")
TOKEN = re.compile(
    r'(?P<comment>//[^\n]*|/\*[\s\S]*?\*/)|'
    r'(?P<literal>"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|'
    r'(?P<code>[\w$]+|[^\s])'
)

# Include direct installs, installer wrappers, callbacks, reflection, resource accesses,
# preference reads and native entry points. Matcher invocations inside resolver helpers
# are counted too, so changing a helper cannot disappear behind a stable caller name.
KINDS = {
    "hook_registration": re.compile(
        r"hook(Method|AllMethods|AllConstructors|Constructor)|findAndHook\w+|"
        r"addListenerActivity|registerActivityLifecycleCallbacks|registerLazyFeature|"
        r"activateFeature(ByName)?|diagnosticTriggered|setOnHierarchyChangeListener"
    ),
    "reflection": re.compile(
        r"get(Declared)?(Fields?|Methods?|Constructors?)|find(Class|Field|Method|Constructor)\w*|"
        r"get(Field|Method|Constructor)Instance|loadClass|forName|"
        r"get(Static)?(Object|Int|Boolean|Long)Field|set(Static)?(Object|Int|Boolean|Long)Field|"
        r"get(Field|Method|Constructor)By\w+|getFieldsBy\w+|newInstance|invoke|setAccessible"
    ),
    "dex_query": re.compile(
        r"find(Method|Class|Field)|get(Class|Method|Field)Data|addUsing\w+|addEqString|"
        r"using(Strings|Numbers|Fields|EqStrings)|add(Invoke|Caller)|"
        r"load[A-Z]\w*"
    ),
    "resource": re.compile(r"getID|getIdentifier|getOfuscateID\w*|getResource\w*"),
    "preference": re.compile(r"get(Boolean|Int|Float|String|Long|StringSet)|getSafe(String|Float)"),
    "native": re.compile(r"loadLibrary|native[A-Z]\w*"),
}
RESOLVER_OWNERS = {"Unobfuscator", "Beta10Resolvers", "HostDexContracts", "HostResolverCompat",
                   "ReflectionUtils", "XposedHelpers", "DexKitClassQuery", "ViewHolderCompat"}
PREF_OWNERS = {"pref", "prefs", "xprefs", "providerPrefs", "preferences", "sharedPreferences"}


def tokens(source):
    return [match.group() for match in TOKEN.finditer(source) if not match.group("comment")]


def call_key(items, index):
    """Capture the call expression including nested matching clauses and literal anchors."""
    depth = 0
    for end in range(index + 1, len(items)):
        if items[end] == "(":
            depth += 1
        elif items[end] == ")":
            depth -= 1
            if depth == 0:
                break
    else:
        end = len(items) - 1
    start = index - 2 if index >= 2 and items[index - 1] == "." else index
    expression = items[start:end + 1]
    digest = hashlib.sha256("\0".join(expression).encode()).hexdigest()[:20]
    # Only the first tokens appear in the manifest. Callback bodies are fingerprinted.
    preview = "".join(expression[:9])
    return f"{preview}#{digest}"


def dependencies(items, host_source):
    found = Counter()
    for i, token in enumerate(items[:-1]):
        if items[i + 1] != "(":
            continue
        receiver = items[i - 2] if i >= 2 and items[i - 1] == "." else ""
        for kind, pattern in KINDS.items():
            if not pattern.fullmatch(token):
                continue
            if kind == "preference" and receiver not in PREF_OWNERS and token not in (
                    "getSafeString", "getSafeFloat"):
                continue
            if kind == "dex_query" and token.startswith("load") and receiver not in RESOLVER_OWNERS:
                continue
            if not host_source and kind not in ("hook_registration", "native") and receiver not in RESOLVER_OWNERS:
                continue
            found[(kind, call_key(items, i))] += 1
            break
    return [[kind, key, count] for (kind, key), count in sorted(found.items())]


def inventory():
    sources = {}
    registrations = []
    for path in sorted((ROOT / "app/src").glob("*/java/**/*")):
        if path.suffix not in (".java", ".kt") or "/src/test" in path.as_posix():
            continue
        src = path.read_text()
        relative = path.relative_to(ROOT).as_posix()
        items = tokens(src)
        if path.name == "FeatureLoader.java":
            code = " ".join(items)
            matches = re.search(r"var classes = new Class < \? > \[ \] \{([^}]+)", code)
            if not matches:
                raise ValueError("FeatureLoader registration format changed")
            registrations = re.findall(r"(\w+) \. class", matches.group(1))
        sites = dependencies(items, "/xposed/" in relative)
        if sites:
            sources[relative] = {"owner": path.stem, "sites": sites}

    if not registrations or len(registrations) != len(set(registrations)):
        raise ValueError("FeatureLoader registrations missing or duplicated")
    return {
        "schema": 1,
        "loader_features": registrations,
        "categories": {
            "hook_registration": ["host and Xposed lifecycle", "exact call-site", "verify installer and callback separately", "runtime L2/L3"],
            "reflection": ["host classloader", "runtime member or dynamic expression", "use feature failure policy", "runtime reflection probe"],
            "dex_query": ["initialized pinned DexKit", "source matcher", "require evidence of uniqueness per resolver", "offline L1 when supported"],
            "resource": ["installed resources and splits", "resource name/type or dynamic expression", "verify missing-resource path", "aapt2 plus installed Resources"],
            "preference": ["owner configuration", "schema key or dynamic expression", "verify defaults and feature independence", "runtime enabled/disabled"],
            "native": ["ABI and process", "native load or call", "verify local failure handling", "ABI and device smoke"],
        },
        "sources": sources,
    }


def render():
    manifest = inventory()
    sources = manifest.pop("sources")
    header = json.dumps(manifest, indent=2)
    entries = ",\n".join(f"    {json.dumps(path)}: {json.dumps(item)}" for path, item in sources.items())
    return header[:-2].rstrip() + ',\n  "sources": {\n' + entries + "\n  }\n}\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--update", action="store_true")
    args = parser.parse_args()
    rendered = render()
    if args.update:
        MANIFEST.write_text(rendered)
    else:
        recorded = MANIFEST.read_text()
        if recorded != rendered:
            for line in difflib.unified_diff(
                    recorded.splitlines(), rendered.splitlines(), fromfile="recorded", tofile="source"):
                print(line[:300])
            raise SystemExit("Review the changed dependency sites before updating the manifest.")
    value = json.loads(rendered)
    print(f"{len(value['loader_features'])} loader registrations, {len(value['sources'])} source owners, "
          f"{sum(site[2] for item in value['sources'].values() for site in item['sites'])} dependency sites")


if __name__ == "__main__":
    main()
