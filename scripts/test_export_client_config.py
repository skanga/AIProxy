"""Offline contract tests for metadata-based client configuration exports."""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import tomllib
import unittest


SCRIPT = Path(__file__).with_name("export-client-config.py")


class ExportClientConfigTest(unittest.TestCase):
    def run_export(self, client, model, *extra):
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "models.json"
            catalog.write_text(json.dumps({"data": [model]}), encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), "--client", client,
                                   "--catalog", str(catalog), "--model", model["id"], *extra],
                                  capture_output=True, text=True)

    def model(self):
        return {"id": "test", "name": "Test Model", "context_length": 128000,
                "top_provider": {"max_completion_tokens": 8192},
                "architecture": {"input_modalities": ["text", "image"]},
                "aiproxy": {"provider": "copilot", "qualified_id": "copilot/test",
                            "reasoning_efforts": ["low", "high"]}}

    def test_pi_uses_qualified_id_and_reported_limits_without_embedding_secrets(self):
        result = self.run_export("pi", self.model())
        self.assertEqual(0, result.returncode, result.stderr)
        provider = json.loads(result.stdout)["providers"]["aiproxy"]
        self.assertEqual("http://127.0.0.1:10531/v1", provider["baseUrl"])
        self.assertEqual("openai-completions", provider["api"])
        self.assertEqual("${AIPROXY_API_KEY}", provider["apiKey"])
        model = provider["models"][0]
        self.assertEqual("copilot/test", model["id"])
        self.assertEqual(128000, model["contextWindow"])
        self.assertEqual(8192, model["maxTokens"])
        self.assertEqual(["text", "image"], model["input"])
        self.assertNotIn("cost", model)

    def test_codex_uses_responses_and_context_limit(self):
        result = self.run_export("codex", self.model())
        self.assertEqual(0, result.returncode, result.stderr)
        config = tomllib.loads(result.stdout)
        self.assertEqual("copilot/test", config["model"])
        self.assertEqual(128000, config["model_context_window"])
        self.assertEqual("low", config["model_reasoning_effort"])
        self.assertEqual("responses", config["model_providers"]["aiproxy"]["wire_api"])
        self.assertEqual("AIPROXY_API_KEY", config["model_providers"]["aiproxy"]["env_key"])

    def test_unknown_limits_require_explicit_values(self):
        model = {"id": "unknown", "aiproxy": {"qualified_id": "codex/unknown"}}
        result = self.run_export("pi", model)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("context", result.stderr.lower())
        self.assertEqual("", result.stdout)
        result = self.run_export("pi", model, "--context-window", "32000", "--max-output-tokens", "4096")
        self.assertEqual(0, result.returncode, result.stderr)
        exported = json.loads(result.stdout)["providers"]["aiproxy"]["models"][0]
        self.assertEqual(32000, exported["contextWindow"])
        self.assertNotIn("reasoning", exported)
        self.assertNotIn("input", exported)

    def test_escapes_model_strings_for_toml(self):
        model = self.model()
        model["aiproxy"]["qualified_id"] = 'codex/test"\nmodel_provider = "evil'
        result = self.run_export("codex", model)
        self.assertEqual(0, result.returncode, result.stderr)
        config = tomllib.loads(result.stdout)
        self.assertEqual(model["aiproxy"]["qualified_id"], config["model"])
        self.assertEqual("aiproxy", config["model_provider"])

    def test_does_not_enable_efforts_rejected_by_messages_translation(self):
        model = self.model()
        model["aiproxy"]["upstream_endpoints"] = ["/v1/messages"]
        model["aiproxy"]["reasoning_efforts"] = ["xhigh", "high"]
        result = self.run_export("pi", model)
        self.assertEqual(0, result.returncode, result.stderr)
        exported = json.loads(result.stdout)["providers"]["aiproxy"]["models"][0]
        self.assertIsNone(exported["thinkingLevelMap"]["xhigh"])
        self.assertEqual("high", exported["thinkingLevelMap"]["high"])

    def test_anthropic_effort_requires_adaptive_thinking_for_translation(self):
        model = self.model()
        model["aiproxy"]["provider"] = "anthropic"
        model["aiproxy"]["qualified_id"] = "anthropic/test"
        model["aiproxy"]["capabilities"] = {"adaptive_thinking": False}
        result = self.run_export("pi", model)
        self.assertEqual(0, result.returncode, result.stderr)
        exported = json.loads(result.stdout)["providers"]["aiproxy"]["models"][0]
        self.assertNotIn("reasoning", exported)

    def test_refuses_invalid_limits_and_non_http_urls(self):
        for value in (0, -1):
            result = self.run_export("pi", self.model(), "--context-window", str(value))
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", result.stdout)
        result = self.run_export("pi", self.model(), "--base-url", "file:///tmp/secret")
        self.assertNotEqual(0, result.returncode)

    def test_pi_rejects_native_codex_profile_that_cannot_accept_its_output_limit(self):
        model = self.model()
        model["aiproxy"].update(provider="codex", qualified_id="codex/test", auth_profile="native")
        result = self.run_export("pi", model)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("native Codex", result.stderr)
        self.assertEqual("", result.stdout)

    def test_fetches_extended_catalog_with_bearer_auth_and_never_exports_key(self):
        requests = []
        payload = json.dumps({"data": [self.model()]}).encode()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append((self.path, self.headers.get("Authorization"), self.headers.get("x-api-key")))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                result = subprocess.run([sys.executable, str(SCRIPT), "--client", "pi", "--model", "test",
                                         "--base-url", f"http://127.0.0.1:{server.server_port}/v1"],
                                        env={**os.environ, "AIPROXY_API_KEY": "dummy-test-secret"},
                                        capture_output=True, text=True)
            finally:
                server.shutdown()
                thread.join()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([("/v1/models", "Bearer dummy-test-secret", None)], requests)
        self.assertNotIn("dummy-test-secret", result.stdout + result.stderr)

    def test_null_optional_limits_produce_actionable_error(self):
        model = self.model()
        model["top_provider"] = None
        result = self.run_export("pi", model)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--max-output-tokens", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
