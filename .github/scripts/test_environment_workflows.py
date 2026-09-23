from pathlib import Path
import unittest

class BotDestinationTest(unittest.TestCase):
    def test_image_bots_never_edit_legacy_runtime_values_or_gateway_verifier(self):
        workflows = Path(__file__).resolve().parents[1]/'workflows'
        for name in ('api-gateway-ci.yml', 'store-service-ci.yml', '_java-service-image.yml'):
            with self.subTest(workflow=name):
                text = (workflows/name).read_text()
                self.assertIn('environments/dev/isolated-values/', text)
                self.assertNotIn('environments/dev/values/', text)
                self.assertNotIn('--verification-script', text)
                self.assertIn('assert_dev_environment.py', text)
