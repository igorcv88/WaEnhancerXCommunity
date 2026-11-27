import unittest

from audit import parse_dexkit
from inventory import dependencies, tokens


class AuditTest(unittest.TestCase):
    def test_source_inventory_ignores_comments_and_tracks_duplicate_sites(self):
        method = 'XposedBridge.hookMethod(resolve(), callback);'
        one = dependencies(tokens(method), True)
        self.assertEqual(dependencies(tokens(method + ' // ' + method), True), one)
        self.assertEqual(dependencies(tokens(method + '\n' + method), True)[0][2], 2)
        self.assertNotEqual(dependencies(tokens(method.replace("resolve", "other")), True), one)

    def test_zero_candidates_and_ambiguous_candidates_are_distinct(self):
        none = "audit\tresult\tc\tstrategy\t0\tRESOLVER_FAILED"
        many = ("audit\tresult\tc\tstrategy\t2\tAMBIGUOUS\n"
                "audit\tmethod\tc\tLA;->m()V\tclasses.dex\thash\n"
                "audit\tmethod\tc\tLB;->n()V\tclasses2.dex\thash")
        self.assertEqual(parse_dexkit(none)["c"]["status"], "RESOLVER_FAILED")
        self.assertEqual(parse_dexkit(many)["c"]["status"], "AMBIGUOUS")

    def test_truncated_output_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_dexkit("audit\tresult\tc\tstrategy\t1\tL1_UNIQUE")


if __name__ == "__main__":
    unittest.main()
