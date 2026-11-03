"""Upload only verified APK artifacts using a dedicated, prefix-limited AWS identity."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request

from build import verify_apk

ACCOUNT = '329995585547'
REGION = 'ap-northeast-2'
BUCKET = 'yamu-internal-apk-329995585547-ap-northeast-2'
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'apk-out'


def validate_manifest(manifest, commit, directory):
    if not re.fullmatch('[0-9a-f]{40}', commit) or manifest.get('source_revision') != commit:
        raise ValueError('Revision mismatch')
    if (manifest.get('variant') != 'customerDebug' or manifest.get('mock_only') is not True
            or manifest.get('real_api_features') != [] or manifest.get('pos_key_present') is not False
            or manifest.get('signature_verified') is not True
            or manifest.get('android_package') != 'com.yamu.app' or manifest.get('min_android_api') != 30):
        raise ValueError('Not a verified mock customer build')
    tests = manifest.get('unit_tests', {})
    if not isinstance(tests.get('tests'), int) or tests['tests'] <= 0 or any(tests.get(k) != 0 for k in ('failures', 'errors', 'skipped')):
        raise ValueError('Unit test gate failed')
    name = 'YAMU-mock-customer-' + commit[:8] + '.apk'
    if manifest.get('apk_filename') != name:
        raise ValueError('Unexpected artifact filename')
    files = [directory / name, directory / (name + '.sha256'), directory / 'build-manifest.json']
    if any(f.is_symlink() or not f.is_file() for f in files):
        raise ValueError('Only regular artifact files may be uploaded')
    digest = hashlib.sha256(files[0].read_bytes()).hexdigest()
    if manifest.get('sha256') != digest or manifest.get('bytes') != files[0].stat().st_size:
        raise ValueError('Artifact checksum mismatch')
    if files[1].read_text().strip() != digest + '  ' + name:
        raise ValueError('Checksum sidecar mismatch')
    return files


def upload_one(client, file, key, commit):
    digest = hashlib.sha256(file.read_bytes()).digest()
    args = {'Bucket': BUCKET, 'Key': key, 'IfNoneMatch': '*',
            'ChecksumSHA256': base64.b64encode(digest).decode(),
            'Metadata': {'sha256': digest.hex(), 'source-revision': commit, 'data-mode': 'mock'},
            'ServerSideEncryption': 'AES256', 'CacheControl': 'private, max-age=3600',
            'ContentType': 'application/vnd.android.package-archive' if file.suffix == '.apk' else
                ('application/json' if file.suffix == '.json' else 'text/plain; charset=utf-8')}
    if file.suffix == '.apk':
        args['ContentDisposition'] = 'attachment; filename="' + file.name + '"'
    try:
        with file.open('rb') as body:
            client.put_object(Body=body, **args)
    except client.exceptions.ClientError as error:
        if error.response['ResponseMetadata']['HTTPStatusCode'] != 412:
            raise
    # Same bytes are an idempotent retry; conflicting data are never overwritten.
    obj = client.head_object(Bucket=BUCKET, Key=key, ChecksumMode='ENABLED')
    if obj.get('ChecksumSHA256') != base64.b64encode(digest).decode() or obj.get('ContentLength') != file.stat().st_size:
        raise ValueError('Existing/uploaded object conflicts with this artifact')


def main():
    import boto3
    from botocore.config import Config
    manifest = json.loads((OUT / 'build-manifest.json').read_text())
    commit = os.environ['GIT_COMMIT']
    files = validate_manifest(manifest, commit, OUT)
    if manifest.get('ci_job') != os.environ['JOB_NAME'] or manifest.get('ci_build') != os.environ['BUILD_NUMBER']:
        raise ValueError('Artifact does not belong to this Jenkins build')
    verify_apk(files[0])
    session = boto3.Session(profile_name='apk-publisher', region_name=REGION)
    if session.client('sts').get_caller_identity()['Account'] != ACCOUNT:
        raise ValueError('Wrong AWS account')
    client = session.client('s3', endpoint_url='https://s3.' + REGION + '.amazonaws.com',
                            config=Config(signature_version='s3v4', s3={'addressing_style': 'virtual'}))
    if not all(client.get_public_access_block(Bucket=BUCKET)['PublicAccessBlockConfiguration'].values()):
        raise ValueError('Private bucket access block is required')
    # Include Jenkins build identity so retries of one build are idempotent; no mutable latest.apk.
    number = os.environ['BUILD_NUMBER']
    if not re.fullmatch('[1-9][0-9]*', number):
        raise ValueError('Invalid build number')
    prefix = 'builds/android/mock/' + commit + '/jenkins-' + number + '/' + manifest['sha256'][:12] + '/'
    for file in files:
        upload_one(client, file, prefix + file.name, commit)
    key = prefix + files[0].name
    url = client.generate_presigned_url('get_object', Params={'Bucket': BUCKET, 'Key': key}, ExpiresIn=86400)
    with urllib.request.urlopen(urllib.request.Request(url, headers={'Range': 'bytes=0-31'}), timeout=30) as response:
        if response.status not in (200, 206) or response.read(4) != b'PK\x03\x04':
            raise ValueError('Signed download verification failed')
    try:
        urllib.request.urlopen('https://' + BUCKET + '.s3.' + REGION + '.amazonaws.com/' + key, timeout=20)
        raise ValueError('Unsigned download must be denied')
    except urllib.error.HTTPError as error:
        if error.code != 403:
            raise
    # Intentional short-lived download capability, archived for authenticated Jenkins readers; never logged.
    (OUT / 'download-url.txt').write_text(url)
    (OUT / 'publication.json').write_text(json.dumps({'bucket': BUCKET, 'apk_key': key,
        'uploaded_artifacts': 3, 'checksums_verified': True, 'private_download_verified': True,
        'download_expires_seconds': 86400}, indent=2))
    print(json.dumps({'uploaded_artifacts': 3, 'checksums_verified': True, 'private_bucket': True}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'uploaded': False, 'error_type': type(error).__name__, 'details_suppressed': True}))
        raise SystemExit(1)
