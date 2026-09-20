"""Summarise the integration test results and publish them as an annotation.

The integration job runs the real services in containers, so its result is the
one that says whether the platform actually works end to end. Job logs and step
summaries need a login to read, even on a public repository; annotations do
not, so the outcome can be confirmed through the API.

Reporting only: always exits 0. Pass or fail is decided by the verify step.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORTS = Path("backend/integration-tests/target/failsafe-reports")


def main():
    reports = sorted(REPORTS.glob("TEST-*.xml"))

    if not reports:
        print("## Integration tests\n")
        print("No failsafe reports were found: the tests did not get as far as running.\n")
        print("::error title=Integration tests::no results were produced")
        return

    tests = failures = errors = skipped = 0
    seconds = 0.0
    lines = []

    for report in reports:
        suite = ET.parse(report).getroot()
        for case in suite.iter("testcase"):
            tests += 1
            failed = case.find("failure") is not None or case.find("error") is not None
            if case.find("failure") is not None:
                failures += 1
            elif case.find("error") is not None:
                errors += 1
            elif case.find("skipped") is not None:
                skipped += 1
            lines.append(f"| {case.get('classname', '').rsplit('.', 1)[-1]} "
                         f"| {case.get('name')} | {'❌' if failed else '✅'} |")
        seconds += float(suite.get("time", 0))

    print("## Integration tests\n")
    print("| Test class | Test | |")
    print("|---|---|---|")
    print("\n".join(lines))
    passed = tests - failures - errors - skipped
    print(f"\n**{tests} tests, {passed} passed, {failures} failed, {errors} errors** "
          f"in {seconds:.0f}s, against real Postgres, Kafka and five services.\n")

    level = "error" if failures or errors else "notice"
    print(f"::{level} title=Integration tests::{tests} tests: {passed} passed, "
          f"{failures} failed, {errors} errors ({seconds:.0f}s)")


if __name__ == "__main__":
    main()
    sys.exit(0)
