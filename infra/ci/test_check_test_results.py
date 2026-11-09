import contextlib
import io
import runpy
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree


CHECK_RESULTS = runpy.run_path(str(Path(__file__).with_name("check-test-results.py")))["check_results"]


class TestResultGateTest(unittest.TestCase):
    def test_only_complete_passing_reports_are_accepted(self):
        cases = [
            ("passing", "<testsuite><testcase/></testsuite>", True),
            ("skipped", "<testsuite><testcase><skipped/></testcase></testsuite>", False),
            ("failure", "<testsuite><testcase><failure/></testcase></testsuite>", False),
            ("error", "<testsuite><testcase><error/></testcase></testsuite>", False),
            ("empty", "<testsuite/>", False),
            ("malformed", "<testsuite>", False),
            ("missing", None, False),
        ]
        for name, xml, accepted in cases:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory)
                if xml is not None:
                    (path / "TEST-example.xml").write_text(xml, encoding="utf-8")
                with contextlib.redirect_stdout(io.StringIO()):
                    if accepted:
                        CHECK_RESULTS(path)
                    else:
                        with self.assertRaises((ValueError, ElementTree.ParseError)):
                            CHECK_RESULTS(path)

    def test_a_skipped_test_in_another_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "TEST-unit.xml").write_text("<testsuite><testcase/></testsuite>")
            (path / "TEST-db.xml").write_text("<testsuite><testcase><skipped/></testcase></testsuite>")
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
                CHECK_RESULTS(path)


if __name__ == "__main__":
    unittest.main()
