#!/usr/bin/env python3
"""Read-only local APK identity, resource and shared DexKit resolver report."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from inventory import ROOT

HERE = Path(__file__).resolve().parent
RESOLVERS = ROOT / "app/src/main/java/com/waenhancer/xposed/core/devkit/HostDexContracts.java"
PACKAGE = re.compile(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'"
                     r"(?: split='([^']+)')?", re.MULTILINE)
CERTIFICATE = re.compile(r"^Signer(?: \([^\n]+\)| #\d+)? certificate SHA-256 digest: ([\da-f]{64})$",
                         re.MULTILINE)
RESOURCE = re.compile(r"^\s*resource (0x[\da-f]{8}) ([a-z_]+/[\w.]+)(?:\s|$)", re.MULTILINE)


def run(*cmd):
    return subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE).stdout


def tool_version(*cmd):
    return subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT).stdout.strip()


def sha256(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def inspect_apk(path, apksigner, aapt2, interesting):
    sign = run(apksigner, "verify", "--verbose", "--print-certs", str(path))
    badging = run(aapt2, "dump", "badging", str(path))
    identity = PACKAGE.search(badging)
    certificates = sorted(set(CERTIFICATE.findall(sign)))
    if not identity or not certificates:
        raise ValueError("Verifier output is missing package identity or certificate digests")
    pkg, code, version, split = identity.groups()
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        dex = sorted(name for name in names if re.fullmatch(r"classes\d*\.dex", name))
    entries = (RESOURCE.findall(run(aapt2, "dump", "resources", str(path)))
               if "resources.arsc" in names else [])
    return {
        "package": pkg,
        "versionName": version,
        "longVersionCode": int(code),
        "split": split or "",
        "sha256": sha256(path),
        "signers": certificates,
        "dex": dex,
        "nativeLibraries": sorted(n for n in names if n.startswith("lib/") and n.endswith(".so")),
        "resources": {name: rid for rid, name in entries if name in interesting},
    }


def parse_dexkit(output):
    records = {}
    for line in output.splitlines():
        if not line.startswith("audit\t"):
            continue
        _, kind, name, *fields = line.split("\t")
        if kind == "result":
            strategy, count, status = fields
            if name in records:
                raise ValueError("Duplicate contract result")
            records[name] = {
                "strategy": strategy, "candidateCount": int(count), "status": status,
                "source": "DEXKIT_DEFINITION_QUERY", "requiresRuntime": True, "candidates": [],
            }
        elif kind == "method":
            descriptor, dex, fingerprint = fields
            records[name]["candidates"].append({
                "descriptor": descriptor, "declaringClass": descriptor.split("->", 1)[0], "dex": dex,
                "structuralSha256": fingerprint, "references": {},
            })
        elif kind == "path":
            descriptors, dex = fields
            records[name]["candidates"].append({"fields": descriptors.split(","), "dex": dex.split(",")})
        else:
            descriptor, ref = fields
            candidate = next(c for c in records[name]["candidates"] if c["descriptor"] == descriptor)
            refs = candidate["references"]
            if kind in {"invoke", "caller"}:
                refs.setdefault(kind, []).append(ref)
            elif kind in {"invoke-count", "caller-count"}:
                refs[kind] = int(ref)
            else:
                raise ValueError(f"Unknown DexKit evidence type {kind}")
    if not records or any(r["candidateCount"] != len(r["candidates"]) for r in records.values()):
        raise ValueError("Incomplete DexKit candidate report")
    return records


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base", required=True, type=Path)
    p.add_argument("--split", action="append", default=[], type=Path)
    p.add_argument("--sdk-build-tools", required=True, type=Path)
    p.add_argument("--jdk", required=True, type=Path)
    p.add_argument("--dexkit-native", required=True, type=Path)
    p.add_argument("--dexkit-classpath", required=True)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()

    apksigner, aapt2 = (str(args.sdk_build_tools.resolve() / name) for name in ("apksigner", "aapt2"))
    javac, java = (str(args.jdk.resolve() / "bin" / name) for name in ("javac", "java"))
    if args.output.resolve() in {path.resolve() for path in [args.base, *args.split]}:
        p.error("The report path must differ from each input APK")
    interesting = set()
    for relative in ("app/src/main/java/com/waenhancer/xposed",):
        for source in (ROOT / relative).rglob("*.java"):
            for name, typ in re.findall(r'getID\("([\w.]+)",\s*"([\w]+)"\)', source.read_text()):
                interesting.add(f"{typ}/{name}")
    # Include the native widget and view IDs used by layout and media interactions.
    interesting.update(("drawable/ic_viewonce", "id/conversation_contact_status",
                        "id/date_wrapper", "id/view_once_media_container_large"))
    apks = [inspect_apk(path, apksigner, aapt2, interesting) for path in [args.base, *args.split]]
    base, *splits = apks
    if base["split"] or base["package"] != "com.whatsapp":
        p.error("Expected an original com.whatsapp base APK; Business is a separate target")
    if any(not split["split"] or split["dex"] or
           any(split[k] != base[k] for k in ("package", "longVersionCode", "signers"))
           or split["versionName"] not in ("", base["versionName"]) for split in splits):
        p.error("Split identity differs or split carries DEX requiring a multi-APK resolver adapter")
    if len({split["split"] for split in splits}) != len(splits):
        p.error("Repeated split name")
    with tempfile.TemporaryDirectory() as classes:
        run(javac, "-cp", args.dexkit_classpath, "-d", classes,
            str(HERE / "OfflineDexAudit.java"), str(RESOLVERS))
        contracts = parse_dexkit(run(java, f"-Djava.library.path={args.dexkit_native.resolve()}",
                                     "-cp", f"{classes}:{args.dexkit_classpath}", "OfflineDexAudit",
                                     str(args.base.resolve())))
    report = {
        "schema": 1,
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "source": "OFFLINE_LOCAL_FIXTURES",
        "tools": {"sdkBuildTools": args.sdk_build_tools.resolve().name,
                  "apksigner": tool_version(apksigner, "version"), "aapt2": tool_version(aapt2, "version"),
                  "dexkit": "2.2.0 (provide matching JVM classes and native library)"},
        "l0": {"base": base, "splits": sorted(splits, key=lambda part: part["split"])},
        "l1": contracts,
        "unobservedResources": sorted(interesting - set().union(*(part["resources"] for part in apks))),
        "androidClassLoaderAndResources": "NOT_TESTED",
        "l2_l3_l4_l5": "NOT_TESTED",
    }
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(f"{base['versionName']} ({base['longVersionCode']}): {len(splits)} verified splits, "
          f"{len(contracts)} offline contract results")


if __name__ == "__main__":
    main()
