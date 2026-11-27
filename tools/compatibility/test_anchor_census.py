import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import anchor_census as census  # noqa: E402

FIXTURE = json.loads(census.FIXTURE.read_text(encoding="utf-8"))
# The two mandatory builds: control and target of PR #177.
APKS = {"2.26.38.74": 263807433, "2.26.46.82": 264608200}


def dump(*methods):
    """Minimal dexdump -d text: (class, method, signature, [constants])."""
    lines = []
    for cls, name, sig, constants in methods:
        lines += [f"  Class descriptor  : '{cls}'", f"      name          : '{name}'",
                  f"      type          : '{sig}'"]
        lines += [f'0000: const-string v0, "{c}" // string@0001' for c in constants]
    return lines


class ParserTest(unittest.TestCase):
    def test_no_candidate_unique_and_ambiguous_are_distinct(self):
        text = dump(("LA;", "a", "()V", ["unique anchor", "shared"]),
                    ("LB;", "b", "()V", ["shared"]))
        result = census.count(text, ["unique anchor", "shared", "missing"])
        self.assertEqual(census.classify(result["missing"]), "ABSENT")
        self.assertEqual(census.classify(result["unique anchor"]), "UNIQUE_METHOD")
        self.assertEqual(census.classify(result["shared"]), "MULTIPLE_METHODS")

    def test_contains_and_equals_are_counted_separately(self):
        text = dump(("LA;", "a", "()V", ["Tag/method failed"]), ("LB;", "b", "()V", ["Tag/method"]))
        result = census.count(text, ["Tag/method"])
        self.assertEqual(result["Tag/method"]["equals"], [1, 1])
        self.assertEqual(result["Tag/method"]["contains"], [2, 2])

    def test_overloads_with_changed_signature_are_different_methods(self):
        text = dump(("LA;", "a", "()V", ["x"]), ("LA;", "a", "(I)V", ["x"]))
        self.assertEqual(census.count(text, ["x"])["x"]["contains"], [2, 1])

    def test_jumbo_strings_are_read(self):
        lines = ["  Class descriptor  : 'LA;'", "      name          : 'a'", "      type          : '()V'",
                 '0000: const-string/jumbo v12, "big" // string@10001']
        self.assertEqual(census.count(lines, ["big"])["big"]["equals"], [1, 1])

    def test_extraction_ignores_comments_and_resolves_constants(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "R.java"
            source.write_text(
                'class R { private static final String ANCHOR = "from constant";\n'
                '// findFirstMethodUsingStrings(l, t, "commented out");\n'
                'void a() { findFirstMethodUsingStrings(l, StringMatchType.Contains, "literal (with parens)");\n'
                '  m.addUsingString(ANCHOR, StringMatchType.Equals); } }', encoding="utf-8")
            self.assertEqual(census.anchors([source]), ["from constant", "literal (with parens)"])


class FixtureTest(unittest.TestCase):
    """Recorded census of the two mandatory APKs; one sub-test per build."""

    def test_both_mandatory_builds_are_recorded_for_their_own_version(self):
        for label, version in APKS.items():
            with self.subTest(apk=label):
                self.assertIn(label, FIXTURE["apks"])
                self.assertEqual(FIXTURE["apks"][label]["versionCode"], version)

    def test_census_covers_every_anchor_in_the_sources(self):
        # A new or changed anchor makes the recorded census stale until it is re-run.
        self.assertEqual(census.check(census.FIXTURE), [])

    def expect(self, anchor, **by_build):
        for label, status in by_build.items():
            with self.subTest(apk=label, anchor=anchor):
                self.assertEqual(census.classify(FIXTURE["apks"][label]["anchors"][anchor]), status)

    def test_video_row_assertion_is_unique_in_both_builds(self):
        self.expect("thumb view type is not correct",
                    **{"2.26.38.74": "UNIQUE_METHOD", "2.26.46.82": "UNIQUE_METHOD"})

    def test_legacy_video_worker_anchor_exists_only_in_the_control(self):
        self.expect("frame_visibility_serial_worker",
                    **{"2.26.38.74": "UNIQUE_METHOD", "2.26.46.82": "ABSENT"})

    def test_incoming_read_receipt_anchor_is_unique_in_both_builds(self):
        self.expect("ReadReceipts/sendReceiptForIncomingMessage",
                    **{"2.26.38.74": "UNIQUE_METHOD", "2.26.46.82": "UNIQUE_METHOD"})

    def test_outgoing_call_log_string_cannot_select_the_launcher(self):
        # Three methods on the control, none on the target: the resolver uses the signature.
        self.expect("outgoing-launch/cm-null-contact",
                    **{"2.26.38.74": "MULTIPLE_METHODS", "2.26.46.82": "ABSENT"})

    def test_header_channel_primary_anchor_replaces_its_lost_fallback(self):
        self.expect("NewsletterHeaderDataItem(enableExploreButton=", **{"2.26.46.82": "UNIQUE_METHOD"})
        self.expect("statusTilesEnabled", **{"2.26.38.74": "UNIQUE_METHOD", "2.26.46.82": "ABSENT"})


@unittest.skipUnless(os.environ.get("WAE_DEXDUMP"), "set WAE_DEXDUMP and WAE_APK_<version> to re-run")
class LiveCensusTest(unittest.TestCase):
    """Re-runs the census on local APKs and compares it with the recorded one."""

    def test_recorded_census_matches_the_apk(self):
        for label in APKS:
            apk = os.environ.get("WAE_APK_" + label.replace(".", "_"))
            if not apk:
                continue
            with self.subTest(apk=label):
                live = census.census(Path(os.environ["WAE_DEXDUMP"]), Path(apk), census.anchors())
                self.assertEqual(live, FIXTURE["apks"][label]["anchors"])


if __name__ == "__main__":
    unittest.main()
