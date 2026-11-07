#!/usr/bin/env python3
"""CI-only: require Gradle AND executed testcase success; diagnose either failure."""
import argparse
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from verify_android_tests import diagnose_device, verify


def run(required: list[str], embedded: bool = False) -> int:
    command = ['gradle', '--stacktrace', '--info', '-PascentAbi=x86_64']
    if embedded:
        command += ['-PascentEmbedded=true',
                    '-Pandroid.testInstrumentationRunnerArguments.class=com.pmixay.ascent.EmbeddedModelRuntimeTest']
    result = subprocess.run(command + [':app:connectedDebugAndroidTest'])
    evidence_ok = False
    try:
        verify(Path('app/build/outputs/androidTest-results'), required)
        evidence_ok = True
    except (ValueError, ET.ParseError) as error:
        print(str(error), flush=True)
    if result.returncode or not evidence_ok:
        diagnose_device(required)
        return result.returncode or 1
    return 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--embedded', action='store_true')
    parser.add_argument('--require', action='append', required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.require, args.embedded))
