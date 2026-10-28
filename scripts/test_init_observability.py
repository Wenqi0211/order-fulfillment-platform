"""Verify local monitoring setup preserves credentials and filesystem permissions."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import init_observability


class MonitoringInitializationTest(unittest.TestCase):
    def test_initialization_is_repeatable_and_preserves_existing_credentials(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            original = 'APP_API_KEY=' + 'a' * 48 + '\nPOSTGRES_PASSWORD=keep-this\n'
            (root / '.env').write_text(original)
            local = init_observability.initialize(root)
            password = (local / 'grafana-admin-password').read_text()
            init_observability.initialize(root)
            self.assertEqual((local / 'grafana-admin-password').read_text(), password)
            self.assertEqual((local / 'app-api-key').read_text(), 'a' * 48)
            self.assertIn('POSTGRES_PASSWORD=keep-this', (root / '.env').read_text())
            self.assertEqual((root / '.env').read_text().count('OBSERVABILITY_UID='), 1)
            for file in [root / '.env', local / 'app-api-key', local / 'grafana-admin-password']:
                self.assertEqual(file.stat().st_mode & 0o777, 0o600)
            self.assertEqual(local.stat().st_mode & 0o777, 0o700)

    def test_missing_or_invalid_application_configuration_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            with self.assertRaises(ValueError):
                init_observability.initialize(root)
            (root / '.env').write_text('APP_API_KEY=short\n')
            with self.assertRaises(ValueError):
                init_observability.initialize(root)
            self.assertEqual((root / '.env').read_text(), 'APP_API_KEY=short\n')

    def test_foreign_uid_is_not_silently_overwritten(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            root = Path(directory)
            (root / '.env').write_text('APP_API_KEY=' + 'a' * 48 + '\nOBSERVABILITY_UID=99999\n')
            with self.assertRaises(ValueError):
                init_observability.initialize(root)


if __name__ == '__main__':
    unittest.main()
