#!/usr/bin/env python3
"""Build human-readable release notes from PRs contained in a Git compare range."""

from __future__ import annotations

import html
import json
import os
import re
import subprocess
from pathlib import Path

REPO = os.environ["GITHUB_REPOSITORY"]
BASE_TAG = os.environ.get("BASE_TAG", "").strip()
HEAD_SHA = os.environ["HEAD_SHA"].strip()
VERSION_NAME = os.environ["VERSION_NAME"].strip()
SHA256 = os.environ["SHA256"].strip()
OUTPUT = Path(os.environ["NOTES_PATH"])


def gh_json(path: str):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def clean_body(body: str | None) -> str:
    if not body:
        return ""
    text = body.replace("\r\n", "\n")
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"<details\b.*?</details>", "", text, flags=re.S | re.I)
    text = re.split(r"\n---\s*\nupdated-dependencies:", text, maxsplit=1, flags=re.I)[0]
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    # Dependabot and generated descriptions can still be extremely large. Keep the authored,
    # readable part without allowing one dependency PR to consume the whole GitHub release body.
    if len(text) > 4000:
        text = text[:4000].rstrip() + "\n\n_Description truncated; open the PR for the remaining detail._"
    return text


def pr_link(number: int) -> str:
    return f"https://github.com/{REPO}/pull/{number}"


pulls: dict[int, dict] = {}
direct_commits: list[dict] = []

if BASE_TAG:
    comparison = gh_json(f"repos/{REPO}/compare/{BASE_TAG}...{HEAD_SHA}")
    commits = comparison.get("commits", [])
    total = int(comparison.get("total_commits", len(commits)))
    if total > len(commits):
        raise SystemExit(
            f"Compare range contains {total} commits but GitHub returned only {len(commits)}; "
            "refusing to publish an incomplete changelog."
        )

    for commit in commits:
        sha = commit["sha"]
        associated = gh_json(f"repos/{REPO}/commits/{sha}/pulls")
        merged = [
            item for item in associated
            if item.get("merged_at") and item.get("base", {}).get("ref") == "master"
        ]
        if not merged:
            direct_commits.append(commit)
            continue
        for item in merged:
            number = int(item["number"])
            if number not in pulls:
                pulls[number] = gh_json(f"repos/{REPO}/pulls/{number}")

ordered = sorted(pulls.values(), key=lambda item: item.get("merged_at") or "")

lines: list[str] = []
lines.append(f"# WaEnhancer Community {VERSION_NAME}")
lines.append("")
lines.append(f"SHA-256: `{SHA256}`")
lines.append("")
if BASE_TAG:
    lines.append(f"Changes since [{BASE_TAG}](https://github.com/{REPO}/releases/tag/{BASE_TAG}).")
else:
    lines.append("Initial release in this release series.")
lines.append("")

if ordered:
    lines.append("## What's changed")
    lines.append("")
    for pr in ordered:
        number = int(pr["number"])
        title = html.unescape(pr.get("title") or "Untitled pull request").strip()
        lines.append(f"- [#{number}]({pr_link(number)}) {title}")
    lines.append("")

    lines.append("## Full changelog")
    lines.append("")
    lines.append("This section compiles the descriptions of the pull requests included in this release.")
    lines.append("")
    for pr in ordered:
        number = int(pr["number"])
        title = html.unescape(pr.get("title") or "Untitled pull request").strip()
        author = (pr.get("user") or {}).get("login") or "unknown"
        lines.append(f"### [#{number} — {title}]({pr_link(number)})")
        lines.append("")
        lines.append(f"Merged by contribution from `@{author}`.")
        lines.append("")
        body = clean_body(pr.get("body"))
        lines.append(body if body else "_No pull-request description was provided._")
        lines.append("")
else:
    lines.append("## What's changed")
    lines.append("")
    lines.append("No merged pull requests were found in this release interval.")
    lines.append("")

if direct_commits:
    lines.append("## Other changes")
    lines.append("")
    lines.append("These commits reached `master` without an associated merged pull request:")
    lines.append("")
    for commit in direct_commits:
        sha = commit["sha"]
        short = sha[:7]
        message = ((commit.get("commit") or {}).get("message") or "Direct commit").splitlines()[0].strip()
        lines.append(f"- [`{short}`](https://github.com/{REPO}/commit/{sha}) {message}")
    lines.append("")

text = "\n".join(lines).rstrip() + "\n"
if len(text.encode("utf-8")) > 120_000:
    raise SystemExit("Generated release notes exceed 120 KB; shorten unusually large PR descriptions.")
OUTPUT.write_text(text, encoding="utf-8")
print(f"Wrote {len(ordered)} PRs and {len(direct_commits)} direct commits to {OUTPUT}")
