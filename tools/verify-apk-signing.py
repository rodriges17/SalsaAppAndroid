"""Require valid APK signatures and the pinned distribution certificate on every APK."""
import os
from pathlib import Path
import re
import subprocess
import sys

expected = os.environ['STEPBYSTEP_SIGNING_CERT_SHA256'].replace(':', '').lower()
if not re.fullmatch('[0-9a-f]{64}', expected):
    sys.exit('Missing or invalid signing certificate pin.')
signer = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0' / 'apksigner'
for apk in sys.argv[1:]:
    result = subprocess.run([str(signer), 'verify', '--verbose', '--print-certs', apk],
                            check=True, text=True, stdout=subprocess.PIPE)
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', result.stdout, re.M)
    if [value.lower() for value in certificates] != [expected]:
        sys.exit(f'Unexpected APK certificate: {Path(apk).name}')
    print(f'{Path(apk).name}: signature valid, certificate {expected}')
