import unittest
from pathlib import Path
from update_migration_image_pair import update_pair

class MigrationPairTest(unittest.TestCase):
    def pair(self, content, **changes):
        args = dict(repository="dorosiya/pawbridge-animal-service",
                    tag="sha-" + "a" * 40, digest="sha256:" + "b" * 64,
                    migration_digest="sha256:" + "c" * 64)
        args.update(changes)
        return update_pair(content, **args)

    def test_first_pair_does_not_enable_migration(self):
        output = self.pair("replicaCount: 1\n")
        self.assertIn("  enabled: false\n", output)
        self.assertIn("sourceRevision: " + "a" * 40, output)
        self.assertEqual(output, self.pair(output))

    def test_active_pair_preserves_approval_and_other_values(self):
        original = ("schemaMigration:\n  enabled: true\n  recoveryReference: reviewed\n"
                    "  existingSchemaVerified: true\n  image: old\n  apiImageDigest: old\n"
                    "env:\n  SPRING_JPA_HIBERNATE_DDL_AUTO: validate\n")
        output = self.pair(original)
        for value in ("enabled: true", "recoveryReference: reviewed",
                      "existingSchemaVerified: true", "DDL_AUTO: validate"):
            self.assertIn(value, output)
        self.assertNotIn(": old", output)
        self.assertEqual(output, self.pair(output))

    def test_rejects_invalid_pair_before_write(self):
        for change in ({"migration_digest": "latest"}, {"digest": "bad"},
                       {"tag": "main"}, {"repository": "example/other"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.pair("replicaCount: 1\n", **change)

    def test_rejects_ambiguous_mapping(self):
        for content in ("schemaMigration: {}\n",
                        "schemaMigration:\nschemaMigration:\n",
                        "schemaMigration:\n  image: old\n  image: other\n"):
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.pair(content)

    def test_crlf_preserved(self):
        output = self.pair("replicaCount: 1\r\n")
        self.assertNotIn("\n", output.replace("\r\n", ""))
        self.assertEqual(output, self.pair(output))

    def test_all_service_repositories_supported(self):
        for service in ("animal", "user", "community", "store", "payment"):
            repo = f"dorosiya/pawbridge-{service}-service"
            with self.subTest(service=service):
                output = self.pair("env: {}\n", repository=repo)
                self.assertIn(f"image: {repo}@sha256:", output)
                self.assertEqual(output, self.pair(output, repository=repo))

    def test_workflows_build_pair_without_broadening_publication(self):
        root = Path(__file__).resolve().parents[1]
        for name, gate in (
            ("store-service-ci.yml",
             "env.STORE_PUBLISH_ALLOWED == 'true' && steps.release.outputs.publish == 'true'"),
            ("_java-service-image.yml", "inputs.publish && github.ref == 'refs/heads/dev'"),
        ):
            with self.subTest(workflow=name):
                workflow = (root / "workflows" / name).read_text()
                self.assertIn("test migrationTest bootJar migrationDistribution", workflow)
                migration_step = workflow.split("      - name: Build and optionally publish migration image\n")[1].split("      - name:")[0]
                self.assertIn("push: ${{ " + gate + " }}", migration_step)
                self.assertIn("build/migration", migration_step)
                self.assertIn("file: .github/migrations/Dockerfile", migration_step)
                self.assertEqual(workflow.count('--migration-digest "$MIGRATION_DIGEST"'), 2)
                self.assertIn("--namespace pawbridge", workflow)

if __name__ == "__main__":
    unittest.main()
