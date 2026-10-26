"""Reject missing, failed, empty or skipped Gradle JUnit XML results in CI."""

import sys
from pathlib import Path
from xml.etree import ElementTree


def check_results(directory: Path) -> None:
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise ValueError("No JUnit XML test reports were produced")

    cases = []
    for report in reports:
        cases.extend(ElementTree.parse(report).getroot().iter("testcase"))
    if not cases:
        raise ValueError("JUnit reports contain no test cases")

    failed = sum(case.find("failure") is not None or case.find("error") is not None for case in cases)
    skipped = sum(case.find("skipped") is not None for case in cases)
    print(f"BE tests: total={len(cases)}, failed={failed}, skipped={skipped}")
    if failed or skipped:
        raise ValueError("CI requires all tests to run and pass, including MySQL tests")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("Usage: check-test-results.py <Gradle test-results directory>")
        check_results(Path(sys.argv[1]))
    except (ValueError, OSError, ElementTree.ParseError) as error:
        print(f"Test result gate failed: {error}", file=sys.stderr)
        sys.exit(1)
