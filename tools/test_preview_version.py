#!/usr/bin/env python3
"""Source-contract checks for a manually built, release-signed PR preview."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
workflow = (root / '.github/workflows/build-pr-apk-artifact.yml').read_text()
assert 'VERSION_NAME="${RELEASE_SERIES}.${MAX_PATCH}+pr${PR_NUMBER}.${SHORT_SHA}"' in workflow
assert 'VERSION_CODE=$((MAX_CODE + 1))' in workflow
assert 'workflow_dispatch:' in workflow

# Both UI paths must remove the build metadata before numerical release comparison.
for path in ('app/src/main/java/com/waenhancer/UpdateChecker.java',
             'app/src/main/java/com/waenhancer/activities/ChangelogActivity.java'):
    source = (root / path).read_text()
    assert "int plusIndex = normalized.indexOf('+');" in source, path
    assert 'normalized = normalized.substring(0, plusIndex);' in source, path

preview = '0.0.5+pr120.abcdef12'
normal = preview.split('+', 1)[0]
assert normal == '0.0.5'
assert [int(part) for part in normal.split('.')] == [0, 0, 5]
assert normal != '0.0.6'
print('PASS: preview version keeps SemVer metadata while update comparators read 0.0.5')
