import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

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



class NewEvidenceRowsTest(unittest.TestCase):
    def test_type_field_count_agreement_and_key_index_rows(self):
        out = "\n".join([
            "audit\tresult\tmessage-class\tanchor\t1\tL1_UNIQUE",
            "audit\tclass\tmessage-class\tLX/1D4;\tclasses.dex\tsuper=java.lang.Object",
            "audit\tresult\tlegacy-key\tfinal-field\t1\tL1_UNIQUE",
            "audit\tfield\tlegacy-key\tLX/1D4;->A0j:LX/1cj;\tclasses.dex",
            "audit\tcount\tlegacy-key\tinstance-fields-of-key-type\t2",
            "audit\tagreement\tlegacy-key\tprimary-equals-legacy\ttrue",
            "audit\tresult\tprops\tanchor\t1\tL1_UNIQUE",
            "audit\tmethod\tprops\tLX/00E;->A0E(LX/00G;LX/00E;Ljava/lang/Boolean;I)Z\tclasses.dex\tab",
            "audit\tkey-index\tprops\tLX/00E;->A0E(LX/00G;LX/00E;Ljava/lang/Boolean;I)Z\t3",
            "audit\tresult\tnav\toverrides\t2\tL1_SET",
            "audit\tmethod\tnav\tLX/18q;->getMaxItemCount()I\tclasses.dex\taa",
            "audit\tmethod\tnav\tLX/Trm;->getMaxItemCount()I\tclasses.dex\tbb",
        ])
        records = parse_dexkit(out)
        self.assertEqual(records["message-class"]["candidates"][0]["superclass"], "java.lang.Object")
        self.assertEqual(records["legacy-key"]["evidence"],
                         {"instance-fields-of-key-type": 2, "primary-equals-legacy": True})
        self.assertEqual(records["props"]["candidates"][0]["keyParameterIndex"], 3)
        self.assertEqual(records["nav"]["status"], "L1_SET")
        self.assertEqual(len(records["nav"]["candidates"]), 2)


if __name__ == "__main__":
    unittest.main()
