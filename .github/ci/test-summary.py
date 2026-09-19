"""Summarise surefire results per module, and fail if tests silently did not run.

Printed to stdout; the workflow appends it to the run's summary page and to the
log. Also lists the tests excluded from CI together with their reasons, so the
exclusions are visible on every run instead of being forgotten.

Counting: results are counted from the <testcase> elements, never from the
<testsuite> tests/failures/errors/skipped attributes. Newer surefire (3.5.x)
writes the results of @Nested classes into the enclosing class's report but
leaves that report's counters at zero, so trusting the counters undercounts.
That is exactly what the first CI run did: 13 nested tests ran and passed, and
the summary showed 23 tests instead of 36.

Guard: a module that has runnable test classes must produce at least one test
result, unless every one of those classes is on the exclusion list. A module
whose tests never execute otherwise looks identical to a passing one. This is
the one condition that makes the script exit non-zero.
"""

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

BACKEND = Path("backend")
EXCLUDES = Path(".github/ci/surefire-excludes.txt")

# Surefire's default includes: Test*, *Test, *Tests, *TestCase.
RUNNABLE_TEST = re.compile(r"^(Test\w*|\w*Test|\w*Tests|\w*TestCase)$")


def excluded_class_names():
    """Simple class names excluded in the excludes file (patterns are **/Name.java)."""
    if not EXCLUDES.is_file():
        return set()
    names = set()
    for line in EXCLUDES.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.add(line.rsplit("/", 1)[-1].removesuffix(".java"))
    return names


def count_results():
    """Per module: [tests, failures, errors, skipped], counted from <testcase>."""
    totals = defaultdict(lambda: [0, 0, 0, 0])
    for report in sorted(BACKEND.glob("*/target/surefire-reports/TEST-*.xml")):
        row = totals[report.parts[1]]
        for case in ET.parse(report).getroot().iter("testcase"):
            row[0] += 1
            if case.find("failure") is not None:
                row[1] += 1
            elif case.find("error") is not None:
                row[2] += 1
            elif case.find("skipped") is not None:
                row[3] += 1
    return totals


def modules_that_ran_nothing(totals, excluded):
    """Modules with runnable, non-excluded test classes but no test results."""
    silent = []
    for test_root in sorted(BACKEND.glob("*/src/test/java")):
        module = test_root.parts[1]
        runnable = sorted(
            source.stem
            for source in test_root.rglob("*.java")
            if RUNNABLE_TEST.match(source.stem) and source.stem not in excluded
        )
        if runnable and totals.get(module, [0])[0] == 0:
            silent.append((module, runnable))
    return silent


def print_results(totals):
    print("## Test results\n")
    if not totals:
        print("No surefire reports were found: the Test step did not get as far as "
              "running tests.\n")
        return
    print("| Module | Tests | Passed | Failed | Errors | Skipped | |")
    print("|---|---:|---:|---:|---:|---:|---|")
    grand = [0, 0, 0, 0]
    for module, (tests, failures, errors, skipped) in sorted(totals.items()):
        passed = tests - failures - errors - skipped
        mark = "❌" if failures or errors else "✅"
        print(f"| {module} | {tests} | {passed} | {failures} | {errors} | {skipped} | {mark} |")
        grand = [g + v for g, v in zip(grand, (tests, failures, errors, skipped))]
    tests, failures, errors, skipped = grand
    passed = tests - failures - errors - skipped
    print(f"| **Total** | **{tests}** | **{passed}** | **{failures}** "
          f"| **{errors}** | **{skipped}** | {'❌' if failures or errors else '✅'} |")
    print()


def print_exclusions():
    # The excludes file is blocks separated by blank lines. A block that contains
    # patterns explains them in its comments; a block with only comments is the
    # file's header.
    if not EXCLUDES.is_file():
        return
    print("## Excluded from CI\n")
    for block in EXCLUDES.read_text(encoding="utf-8").strip().split("\n\n"):
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        reason = " ".join(line.lstrip("# ") for line in lines if line.startswith("#"))
        for pattern in (line for line in lines if not line.startswith("#")):
            name = pattern.rsplit("/", 1)[-1].removesuffix(".java")
            print(f"- `{name}`: {reason}")
    print()


def annotate(totals, silent):
    """Emit the totals as a GitHub annotation.

    Logs and step summaries need a login to read, even on a public repository;
    annotations do not. Publishing the numbers this way lets anyone confirm a
    run's real counts through the API. Reporting only: the guard itself is
    enforced by the summary step.
    """
    tests, failures, errors, skipped = (sum(col) for col in zip(*totals.values())) if totals else (0, 0, 0, 0)
    passed = tests - failures - errors - skipped
    print(f"::notice title=Test totals::{tests} tests: {passed} passed, "
          f"{failures} failed, {errors} errors, {skipped} skipped")
    for module, classes in silent:
        print(f"::error title=Tests did not run::{module} produced no test results "
              f"({', '.join(classes)})")


def main():
    totals = count_results()

    if "--annotate" in sys.argv:
        annotate(totals, modules_that_ran_nothing(totals, excluded_class_names()))
        return

    print_results(totals)
    print_exclusions()

    silent = modules_that_ran_nothing(totals, excluded_class_names())
    print("## Execution guard\n")
    if silent:
        print("❌ These modules have test classes that should have run but produced "
              "no test results at all:\n")
        for module, classes in silent:
            print(f"- **{module}**: {', '.join(classes)}")
        print("\nEither the tests did not execute, or their reports were not "
              "written. Add a class to the exclusion list, with a reason, only if "
              "it genuinely cannot run in CI.")
        sys.exit(1)
    print("✅ Every module with runnable test classes produced test results.")


if __name__ == "__main__":
    main()
