#!/usr/bin/env python3
"""Print a Codex or pi config snippet from the proxy's model catalog (Python 3.11+)."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


MAX_CATALOG_BYTES = 4 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Catalog redirects are not followed; use the final proxy base URL")


def positive(value):
    result = int(value)
    if result <= 0:
        raise argparse.ArgumentTypeError("Token limits must be positive integers")
    return result


def limit(override, advertised, flag):
    value = override if override is not None else advertised
    if type(value) is not int or value <= 0:
        raise ValueError(f"Unknown or invalid {flag}; supply --{flag} with a verified limit")
    return value


def base_url(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Base URL must be an HTTP(S) proxy URL without embedded credentials")
    if parsed.query or parsed.fragment:
        raise ValueError("Base URL cannot include a query or fragment")
    return value.rstrip("/")


def object_field(value, key):
    child = value.get(key)
    if child is None:
        return {}
    if not isinstance(child, dict):
        raise ValueError(f"Invalid catalog object: {key}")
    return child


def load_catalog(args):
    if args.catalog:
        with Path(args.catalog).open("rb") as file:
            data = file.read(MAX_CATALOG_BYTES + 1)
    else:
        headers = {"Accept": "application/json"}
        key = os.environ.get(args.api_key_env)
        if key:
            headers["Authorization"] = "Bearer " + key
        request = urllib.request.Request(args.base_url + "/models", headers=headers)
        with urllib.request.build_opener(NoRedirect).open(request, timeout=15) as response:
            data = response.read(MAX_CATALOG_BYTES + 1)
    if len(data) > MAX_CATALOG_BYTES:
        raise ValueError("Catalog exceeds 4 MiB")
    catalog = json.loads(data)
    if not isinstance(catalog, dict) or not isinstance(catalog.get("data"), list):
        raise ValueError("Expected a /v1/models response with a data array")
    matches = [model for model in catalog["data"] if isinstance(model, dict)
               and (model.get("id") == args.model
                    or object_field(model, "aiproxy").get("qualified_id") == args.model)]
    if len(matches) != 1:
        raise ValueError("Model must match exactly one catalog entry; use its qualified ID")
    return matches[0]


def export(args, model):
    metadata = object_field(model, "aiproxy")
    if args.client == "pi" and metadata.get("auth_profile") == "native":
        raise ValueError("pi's standard Chat Completions config sends an output-token limit, which native Codex "
                         "does not accept. Select a Codex CLI-profile, Copilot, or Anthropic route instead")
    model_id = metadata.get("qualified_id", model["id"])
    context = limit(args.context_window, model.get("context_length"), "context-window")
    efforts = metadata.get("reasoning_efforts", [])
    if not isinstance(efforts, list):
        efforts = []
    efforts = [value for value in efforts if isinstance(value, str)]
    # These clients use the proxy's OpenAI APIs. Native Messages may expose
    # additional efforts that the proxy's Messages translator cannot encode.
    if metadata.get("provider") == "anthropic":
        if object_field(metadata, "capabilities").get("adaptive_thinking") is not True:
            efforts = []
        efforts = [value for value in efforts if value in ("low", "medium", "high", "max")]
    elif metadata.get("provider") == "copilot" and metadata.get("upstream_endpoints") == ["/v1/messages"]:
        efforts = [value for value in efforts if value in ("low", "medium", "high", "max")]
    if args.client == "codex":
        # JSON string escaping is also valid for these TOML basic strings.
        quote = lambda value: json.dumps(value, ensure_ascii=False)
        lines = [f"model = {quote(model_id)}", 'model_provider = "aiproxy"',
                 f"model_context_window = {context}"]
        for effort in ("medium", "low", "high", "xhigh", "minimal", "none", "max"):
            if effort in efforts:
                lines.append(f"model_reasoning_effort = {quote(effort)}")
                break
        lines += ["", "[model_providers.aiproxy]", 'name = "AIProxyOauth"',
                  f"base_url = {quote(args.base_url)}", f"env_key = {quote(args.api_key_env)}",
                  'wire_api = "responses"']
        return "\n".join(lines)

    output = limit(args.max_output_tokens, object_field(model, "top_provider").get("max_completion_tokens"),
                   "max-output-tokens")
    entry = {"id": model_id, "name": model.get("name", model_id),
             "contextWindow": context, "maxTokens": output}
    modalities = object_field(model, "architecture").get("input_modalities", [])
    if isinstance(modalities, list):
        supported = [value for value in modalities if value in ("text", "image")]
        if supported:
            entry["input"] = supported
    if efforts:
        entry["reasoning"] = True
        entry["thinkingLevelMap"] = {level: level if level in efforts else None
                                     for level in ("off", "minimal", "low", "medium", "high", "xhigh", "max")}
        if "none" in efforts:
            entry["thinkingLevelMap"]["off"] = "none"
    provider = {"baseUrl": args.base_url, "api": "openai-completions",
                "apiKey": "${" + args.api_key_env + "}", "models": [entry]}
    return json.dumps({"providers": {"aiproxy": provider}}, indent=2, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", choices=("codex", "pi"), required=True)
    parser.add_argument("--model", required=True, help="Exact listed or provider-qualified model ID")
    parser.add_argument("--base-url", default="http://127.0.0.1:10531/v1", help="Proxy API base URL, including /v1")
    parser.add_argument("--catalog", help="Read a saved /v1/models JSON response instead of fetching")
    parser.add_argument("--api-key-env", default="AIPROXY_API_KEY", help="Environment variable containing a proxy key")
    parser.add_argument("--context-window", type=positive, help="Explicit verified context-window override")
    parser.add_argument("--max-output-tokens", type=positive, help="Explicit verified output limit for pi")
    args = parser.parse_args()
    try:
        args.base_url = base_url(args.base_url)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", args.api_key_env):
            raise ValueError("Invalid API key environment variable name")
        result = export(args, load_catalog(args))
    except urllib.error.HTTPError as error:
        print(f"Cannot fetch model catalog: HTTP {error.code}", file=sys.stderr)
        return 1
    except (ValueError, OSError) as error:
        print(f"Cannot export client configuration: {error}", file=sys.stderr)
        return 1
    print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
