"""Build the existing mock-only customerDebug variant. Never reads release/API credentials."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'fe/android'
OUT = ROOT / 'apk-out'
SDK = Path('/opt/android-sdk')


def revision():
    value = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if not re.fullmatch('[0-9a-f]{40}', value) or value != os.environ.get('GIT_COMMIT'):
        raise ValueError('Checkout revision differs from Jenkins revision')
    if subprocess.check_output(['git', 'diff', '--name-only', 'HEAD', '--', 'fe/android'], cwd=ROOT).strip():
        raise ValueError('Android source has tracked modifications')
    return value


def require_mock_config(text):
    for field in ('REAL_API', 'POS_KEY'):
        if not re.search(r'\b' + field + r'\s*=\s*""\s*;', text):
            raise ValueError('BuildConfig must contain no real API features or POS key')
    if not re.search(r'\bDEBUG\s*=\s*(?:Boolean\.parseBoolean\("true"\)|true)', text):
        raise ValueError('Only debug builds are supported')


def test_totals(directory):
    files = sorted(directory.glob('TEST-*.xml'))
    result = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for file in files:
        suite = ET.parse(file).getroot()
        cases = list(suite.iter('testcase'))
        if not cases or int(suite.get('tests', str(len(cases)))) != len(cases):
            raise ValueError('JUnit test counts must match actual test cases')
        result['tests'] += len(cases)
        for key, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
            actual = sum(case.find(tag) is not None for case in cases)
            declared = int(suite.get(key, str(actual)))
            if declared != actual:
                raise ValueError('JUnit result counts are inconsistent')
            result[key] += actual
    if not result['tests'] or any(result[k] for k in ('failures', 'errors', 'skipped')):
        raise ValueError('Customer unit tests must pass with no missing/skipped results')
    return result


def verify_apk(apk):
    if apk.is_symlink() or not apk.is_file():
        raise ValueError('Expected one regular APK')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if not {'AndroidManifest.xml', 'classes.dex'}.issubset(names):
            raise ValueError('APK structure is incomplete')
        if any(Path(n).name in {'local.properties', '.env'} or n.endswith(('.jks', '.keystore')) for n in names):
            raise ValueError('Credential source must not be packaged')
    tools = SDK / 'build-tools/37.0.0'
    subprocess.run([str(tools / 'apksigner'), 'verify', str(apk)], check=True, capture_output=True)
    text = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    if not re.search(r"^package: name='com\.yamu\.app' ", text, re.M) or "minSdkVersion:'30'" not in text:
        raise ValueError('Only Android 11+ YAMU customer APKs are supported')


def main():
    commit = revision()
    # The local loader intentionally changes data sources and signing: never inherit it in CI.
    if (APP / 'local.properties').exists():
        raise ValueError('local.properties is forbidden in the mock build')
    if OUT.exists():
        raise ValueError('Output directory must be fresh; do not reuse a previous build')
    OUT.mkdir()
    init = OUT / 'ci-memory.init.gradle'
    init.write_text('allprojects { tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { maxHeapSize = "256m"; maxParallelForks = 1 } }\n')
    subprocess.run(['sh', './gradlew', '--no-daemon', '--max-workers=1', '--console=plain',
                    '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=128m -Dfile.encoding=UTF-8',
                    '-Pkotlin.compiler.execution.strategy=in-process', '--init-script', str(init),
                    'testCustomerDebugUnitTest', 'assembleCustomerDebug'], cwd=APP, check=True)
    config = list((APP / 'app/build/generated/source/buildConfig/customer/debug').rglob('BuildConfig.java'))
    if len(config) != 1:
        raise ValueError('Expected one customerDebug BuildConfig')
    require_mock_config(config[0].read_text())
    results = APP / 'app/build/test-results/testCustomerDebugUnitTest'
    tests = test_totals(results)
    apks = list((APP / 'app/build/outputs/apk/customer/debug').glob('*.apk'))
    if len(apks) != 1:
        raise ValueError('Expected exactly one customerDebug APK')
    verify_apk(apks[0])
    apk = OUT / ('YAMU-mock-customer-' + commit[:8] + '.apk')
    shutil.copyfile(apks[0], apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (OUT / (apk.name + '.sha256')).write_text(digest + '  ' + apk.name + '\n')
    manifest = {'source_revision': commit, 'variant': 'customerDebug', 'mock_only': True,
                'real_api_features': [], 'pos_key_present': False, 'apk_filename': apk.name,
                'bytes': apk.stat().st_size, 'sha256': digest, 'unit_tests': tests,
                'android_package': 'com.yamu.app', 'min_android_api': 30,
                'signature_verified': True, 'physical_device_execution_verified': False,
                'ci_job': os.environ['JOB_NAME'], 'ci_build': os.environ['BUILD_NUMBER']}
    (OUT / 'build-manifest.json').write_text(json.dumps(manifest, indent=2))
    shutil.copytree(results, OUT / 'test-results')
    print(json.dumps({'apk_ready': True, 'tests': tests['tests'], 'mock_only': True}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # SDK/JDBC-style exceptions may expose configuration; log the type only.
        print(json.dumps({'apk_ready': False, 'error_type': type(error).__name__}))
        raise SystemExit(1)
