"""Prove the live Gemini test behaved as its environment requires.

GeminiFunctionCallingLiveTest runs only when GEMINI_API_KEY is set
(@EnabledIfEnvironmentVariable). This checks the surefire report instead of
trusting that:

  * the report must exist. If the test was renamed or dropped there would be
    nothing to skip, and "it skipped" would be a false positive;
  * with no key, the test must have been skipped;
  * with a key, it must have run and passed. Adding the secret later then
    needs no change here.

Exits non-zero, failing the job, on any mismatch.
"""

import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORT = Path(
    "backend/AICommerceAgent/target/surefire-reports/"
    "TEST-com.digitalbank.aicommerce.llm.GeminiFunctionCallingLiveTest.xml"
)


IN_ACTIONS = bool(os.environ.get("GITHUB_ACTIONS"))


def fail(message: str) -> None:
    print(f"FAIL: {message}")
    if IN_ACTIONS:
        # Annotations can be read through the public API without logging in,
        # unlike the job log.
        print(f"::error title=Gemini live test::{message}")
    sys.exit(1)


def ok(message: str) -> None:
    print(f"OK: {message}")
    if IN_ACTIONS:
        print(f"::notice title=Gemini live test::OK: {message}")


if not REPORT.is_file():
    fail(f"report not found at {REPORT}; the live test was not discovered, "
         "so a skip cannot be confirmed")

suite = ET.parse(REPORT).getroot()
tests = int(suite.get("tests", 0))
skipped = int(suite.get("skipped", 0))
failures = int(suite.get("failures", 0))
errors = int(suite.get("errors", 0))

key_present = bool(os.environ.get("GEMINI_API_KEY"))

print(f"GEMINI_API_KEY present: {key_present}")
print(f"report: tests={tests} skipped={skipped} failures={failures} errors={errors}")

if tests < 1:
    fail("report lists no tests")

if key_present:
    if skipped or failures or errors:
        fail("key is present, so the live test must run and pass")
    ok("key present, live test ran and passed")
else:
    if skipped != tests:
        fail("no key is present, so the live test must be skipped, "
             "but it ran against the real API")
    ok("no key present, live test was skipped")
