"""Restore the private distribution key from GitHub Secrets; never generate a fallback key."""
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys


def prepare():
    encoded = os.environ.get('STEPBYSTEP_SIGNING_KEYSTORE_BASE64', '')
    password = os.environ.get('STEPBYSTEP_SIGNING_PASSWORD', '')
    expected = os.environ.get('STEPBYSTEP_SIGNING_CERT_SHA256', '').replace(':', '').lower()
    if not encoded or not password or not re.fullmatch('[0-9a-f]{64}', expected):
        raise ValueError('Configure the two STEPBYSTEP_SIGNING secrets and the certificate SHA256 variable before publishing an APK.')
    destination = Path(os.environ['RUNNER_TEMP']) / 'stepbystep-signing' / 'distribution.p12'
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        key = base64.b64decode(encoded, validate=True)
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            os.chmod(destination, 0o600)
            output.write(key)
        certificate = subprocess.run(
            ['keytool', '-exportcert', '-alias', 'stepbystep', '-keystore', str(destination),
             '-storepass:env', 'STEPBYSTEP_SIGNING_PASSWORD'],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        ).stdout
        if hashlib.sha256(certificate).hexdigest() != expected:
            raise ValueError('Signing certificate changed. Refusing to publish an incompatible APK.')
        with open(os.environ['GITHUB_ENV'], 'a') as environment:
            environment.write(f'STEPBYSTEP_SIGNING_STORE={destination}\n')
        print(f'Distribution signing certificate verified: {expected}')
    except Exception:
        destination.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    try:
        prepare()
    except Exception as error:
        detail = str(error) if isinstance(error, ValueError) else error.__class__.__name__
        print(f'Signing setup failed: {detail}. No APK will be published.', file=sys.stderr)
        sys.exit(1)
