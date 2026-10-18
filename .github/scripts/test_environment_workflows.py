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

    def test_only_community_omits_the_shared_mysql_fixture(self):
        # Empty image disables a service container; the truthy image must be the && branch.
        workflow = Path(__file__).resolve().parents[1] / 'workflows' / '_java-service-image.yml'
        text = workflow.read_text()
        self.assertIn("image: ${{ inputs.service != 'community-service' && 'mysql:8.0' || '' }}", text)
        self.assertIn('MYSQL_DATABASE: pawbridge_ci', text)
        self.assertIn('MYSQL_ROOT_PASSWORD: ci_root_placeholder', text)
