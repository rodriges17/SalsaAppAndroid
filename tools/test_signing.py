import base64
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('prepare-signing.py')


class SigningRestoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.password = 'local-signing-test-password'
        cls.environment = dict(os.environ, STEPBYSTEP_SIGNING_PASSWORD=cls.password)
        cls.stores = []
        cls.certificates = []
        for index in range(2):
            path = Path(cls.keys.name) / f'key-{index}.p12'
            subprocess.run(['keytool', '-genkeypair', '-alias', 'stepbystep', '-keyalg', 'RSA',
                            '-keysize', '2048', '-validity', '30', '-dname', 'CN=Signing Test',
                            '-storetype', 'PKCS12', '-keystore', str(path),
                            '-storepass:env', 'STEPBYSTEP_SIGNING_PASSWORD'],
                           env=cls.environment, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            cert = subprocess.check_output(['keytool', '-exportcert', '-alias', 'stepbystep',
                                            '-keystore', str(path), '-storepass:env', 'STEPBYSTEP_SIGNING_PASSWORD'],
                                           env=cls.environment, stderr=subprocess.DEVNULL)
            cls.stores.append(base64.b64encode(path.read_bytes()).decode())
            cls.certificates.append(hashlib.sha256(cert).hexdigest())

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def restore(self, directory, **overrides):
        environment = dict(self.environment, RUNNER_TEMP=str(directory), GITHUB_ENV=str(directory / 'env'),
                           STEPBYSTEP_SIGNING_KEYSTORE_BASE64=self.stores[0],
                           STEPBYSTEP_SIGNING_CERT_SHA256=self.certificates[0])
        environment.update(overrides)
        return subprocess.run(['python3', str(SCRIPT)], env=environment, capture_output=True, text=True)

    def test_independent_restores_preserve_the_same_private_key(self):
        restored = []
        for _ in range(2):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result = self.restore(root)
                self.assertEqual(result.returncode, 0, result.stderr)
                path = root / 'stepbystep-signing' / 'distribution.p12'
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                restored.append(path.read_bytes())
                self.assertIn(str(path), (root / 'env').read_text())
                self.assertNotIn(self.stores[0], result.stdout + result.stderr)
                self.assertNotIn(self.password, result.stdout + result.stderr)
        self.assertEqual(restored[0], restored[1])

    def test_changed_certificate_missing_key_bad_encoding_and_password_fail_closed(self):
        cases = [
            {'STEPBYSTEP_SIGNING_KEYSTORE_BASE64': self.stores[1]},
            {'STEPBYSTEP_SIGNING_KEYSTORE_BASE64': ''},
            {'STEPBYSTEP_SIGNING_KEYSTORE_BASE64': 'invalid-base64'},
            {'STEPBYSTEP_SIGNING_PASSWORD': 'incorrect'},
            {'STEPBYSTEP_SIGNING_CERT_SHA256': ''},
        ]
        for overrides in cases:
            with self.subTest(fields=list(overrides)), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result = self.restore(root, **overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((root / 'env').exists())
                self.assertFalse((root / 'stepbystep-signing' / 'distribution.p12').exists())
                self.assertNotIn(self.stores[0], result.stdout + result.stderr)
                self.assertNotIn(self.password, result.stdout + result.stderr)
