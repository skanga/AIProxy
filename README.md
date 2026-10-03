# AIProxy 5.1

Use GitHub Copilot, ChatGPT/Codex, and Anthropic accounts from applications that support an OpenAI-compatible API. Anthropic clients can also connect through the native Messages API.

Provider credentials stay on the machine running the proxy. Client applications connect to the proxy's local URL and can use separate proxy API keys.

## Quick start

You need **Java 21** and access to at least one supported provider.

1. Download the JAR from [GitHub Releases](https://github.com/skanga/AIProxy/releases). Choose the `AIProxy-<version>.jar` asset, then open a terminal in the folder where you saved it. The examples below use `AIProxy-5.1.jar`; substitute your downloaded filename if different.

2. Log in to a provider. Choose one:

   ```bash
   java -jar AIProxy-5.1.jar auth copilot login
   java -jar AIProxy-5.1.jar auth codex login
   java -jar AIProxy-5.1.jar auth anthropic login
   ```

3. Start the proxy:

   ```bash
   java -jar AIProxy-5.1.jar
   ```

4. Configure your client:

   | Setting | Value |
   |---|---|
   | API type | OpenAI-compatible |
   | Base URL | `http://127.0.0.1:10531/v1` |
   | API key | Any nonempty placeholder, such as `local`, until you enable proxy API keys |
   | Model | An ID from the startup output or `/v1/models` |

By default, the proxy listens only on your machine, enables providers with available credentials, and runs an inference check for each enabled provider. These checks can consume provider quota. To start without sending test prompts:

```bash
java -jar AIProxy-5.1.jar serve --startup-check credentials
```

Stop the proxy with **Ctrl+C**.

## Log in and manage accounts

Check your saved authentication settings with:

```bash
java -jar AIProxy-5.1.jar auth status
```

Use the same `--config FILE` for login and serving if you customize credential locations. Place options after the final subcommand, for example `auth anthropic login --config production.yaml` or `serve --config production.yaml`.

Proxy-managed logins default to `~/.aiproxy/` on every platform (`~` is the home directory of the user running Java):

| Provider | Default file |
|----------|--------------|
| Copilot | `~/.aiproxy/copilot-auth.json` |
| Native Codex | `~/.aiproxy/codex-auth.json` |
| Anthropic | `~/.aiproxy/anthropic-auth.json` |

Explicit CLI, environment, and YAML path overrides still take precedence, in that order. Codex CLI/device-login credentials retain their separate `CODEX_HOME` / `~/.codex/auth.json` locations.

### GitHub Copilot

```bash
java -jar AIProxy-5.1.jar auth copilot login
java -jar AIProxy-5.1.jar serve --provider copilot
```

Follow the printed device-login instructions. If your login expires, run the login command again.

To supply an existing token, set `AIPROXY_COPILOT_TOKEN` or use `--copilot-token-file PATH`. The file can contain a raw token, a proxy credential file, or a Copilot CLI JSONC configuration. An explicit file takes precedence over the environment token and saved login.

For GitHub Enterprise Cloud, use `--copilot-github-host tenant.ghe.com` for both login and serving, with separate credentials. Enterprise Cloud has not been live-validated; GitHub Enterprise Server is unsupported.

To remove the proxy's saved login:

```bash
java -jar AIProxy-5.1.jar auth copilot logout
```

This leaves explicitly supplied token files untouched.

### ChatGPT / Codex

For the proxy's native ChatGPT login:

```bash
java -jar AIProxy-5.1.jar auth codex login
java -jar AIProxy-5.1.jar serve --provider codex --codex-auth-mode native
```

Complete sign-in in a browser on the same computer. Use `--no-browser` to print the URL instead of opening it automatically. Account access to native sign-in is required.

If you already use the official Codex CLI, you can use its saved login instead:

```bash
java -jar AIProxy-5.1.jar serve --provider codex --codex-auth-mode cli
```

For device login, including from a headless machine, install the official Codex CLI on `PATH`, then run:

```bash
java -jar AIProxy-5.1.jar auth codex login --device-auth
java -jar AIProxy-5.1.jar serve --provider codex --codex-auth-mode cli
```

Enable device-code login in your ChatGPT account or workspace settings if required. Device login uses CLI credentials and can replace an existing CLI login. To choose a destination, pass `--codex-oauth-file PATH` to both commands; the filename must be `auth.json`.

| `--codex-auth-mode` | When to use it |
|---|---|
| `auto` (default) | Prefer an explicitly configured CLI file, then a native login, then an existing Codex CLI login |
| `native` | Use only the proxy's native login; customize its location with `--codex-native-auth-file PATH` |
| `cli` | Use Codex CLI credentials; customize their location with `--codex-oauth-file PATH` |

To switch the native account, run `auth codex login --new-account`. **Restart the proxy after changing the login.** Only one native account is active at a time.

To log out of the native account:

```bash
java -jar AIProxy-5.1.jar auth codex logout --yes
```

CLI credentials are separate. Remove them with the official `codex logout`, using the same `CODEX_HOME` and credential-storage settings used for login. In `auto` mode, a remaining CLI login may be selected after native logout.

Native mode has request restrictions: omit temperature, top-p, token-limit, metadata, and background-execution options. Unsupported options return HTTP 400. Use the default native endpoint and settings; CLI endpoint overrides, `--codex-store`, and prompt-cache header forwarding are incompatible with native mode.

### Anthropic

```bash
java -jar AIProxy-5.1.jar auth anthropic login
java -jar AIProxy-5.1.jar serve --provider anthropic
```

For SSH or a headless machine:

```bash
java -jar AIProxy-5.1.jar auth anthropic login --no-browser
```

Open the printed URL on another device and paste the returned `code#state` into the original terminal. Without an interactive console, add `--allow-stdin-oauth-code`.

You can also supply `CLAUDE_CODE_OAUTH_TOKEN`. To customize the saved login location, use `--anthropic-oauth-file PATH` for both login and serving.

```bash
java -jar AIProxy-5.1.jar auth anthropic logout
```

## Connect a client and choose a model

List the models available through the running proxy:

```bash
curl http://127.0.0.1:10531/v1/models
```

In Windows PowerShell, use `curl.exe` if `curl` refers to a PowerShell alias. If proxy authentication is enabled, add `-H "Authorization: Bearer YOUR_PROXY_KEY"`.

Copy a returned model ID into your client. Copilot IDs include `copilot/`. You can explicitly select another provider with `codex/<model-id>` or `anthropic/<model-id>`. Qualify the model when multiple providers offer the same ID.

The OpenAI-compatible model list includes optional OpenRouter-style metadata: `name`, `context_length`, `top_provider.max_completion_tokens`, `architecture.input_modalities`, and a known subset of `supported_parameters`. These are extensions, not standard OpenAI model fields. Missing fields mean unknown, not unlimited or unsupported. Limits come from the authenticated provider catalog; configured-only models and fallback model names do not acquire guessed limits.

Each entry also has an `aiproxy` object with `provider`, `qualified_id`, and `metadata_source` (`upstream`, `configured`, `seed`, or `unknown`). When available it includes `fetched_at`, `max_input_tokens`, upstream `capabilities`, `reasoning_efforts`, and `upstream_endpoints`. Discovered Codex entries also identify their `auth_profile` as `native` or `cli`. Context length, input limits, and output limits are distinct; do not add them together. `fetched_at` is the snapshot's original fetch time, including when served from cache or last-good fallback. Upstream capabilities describe model facts, not a guarantee that every feature works through every proxy protocol. No pricing is inferred.

For a simple Chat Completions request, save this as `request.json`, replacing `MODEL_ID`:

```json
{
  "model": "MODEL_ID",
  "messages": [{"role": "user", "content": "Hello!"}]
}
```

Then send it:

```bash
curl http://127.0.0.1:10531/v1/chat/completions -H "Content-Type: application/json" --data-binary "@request.json"
```

For streaming, add `"stream": true` to the JSON and use `curl -N`.

| Client or task | URL |
|---|---|
| OpenAI-compatible client base URL | `http://127.0.0.1:10531/v1` |
| Chat Completions request | `POST /v1/chat/completions` |
| Responses request | `POST /v1/responses` |
| Anthropic client base URL | `http://127.0.0.1:10531` |
| Anthropic Messages request | `POST /v1/messages` (Anthropic or eligible Copilot models) |
| Available models | `GET /v1/models` |
| Token usage | `GET /v1/usage` |
| Health check | `GET /health` |

Proxy API keys go in `Authorization: Bearer YOUR_PROXY_KEY`; Anthropic clients can use `x-api-key` instead. Do not put provider OAuth tokens in client configuration.

If a Responses continuation fails after a restart or account change, resend the full conversation instead of the previous response ID. See [API compatibility](COMPATIBILITY.md) for supported request features and limitations.

### Model metadata in Codex, Claude Code, and pi

Clients do not all consume OpenRouter-style fields automatically. Codex supports an explicit `model_context_window` in its [configuration](https://developers.openai.com/codex/config-reference); pi supports `contextWindow` and `maxTokens` in its [custom model configuration](https://pi.dev/docs/latest/models). The repository includes a Python 3.11+ helper that fetches the catalog and prints a configuration snippet for one model:

```bash
python scripts/export-client-config.py --client codex --model copilot/MODEL_ID > codex-proxy.toml
python scripts/export-client-config.py --client pi --model copilot/MODEL_ID > pi-proxy.json
```

Set `AIPROXY_API_KEY` to your proxy key before fetching; in open mode, use a placeholder such as `local-proxy` so the clients can resolve a credential. The generated files reference that environment variable and never embed its value. Use `--base-url http://HOST:PORT/v1` for another proxy, or `--catalog models.json` to convert a saved OpenAI-compatible model-list response offline. The helper never changes client settings itself.

Review and merge the Codex snippet into your Codex `config.toml`, or merge the pi provider entry into `~/.pi/agent/models.json`. Codex uses Responses; pi uses Chat Completions. Both exports pin the provider-qualified model ID so its limits stay attached to the same route. pi exports reported text/image input modes and compatible reasoning-level mappings; Codex chooses an advertised compatible effort when known. If a required limit is missing, the helper stops: supply a verified `--context-window N` and, for pi, `--max-output-tokens N`. Unknown capabilities are omitted and client defaults may still apply. Regenerate snippets after changing models or account limits. These config shapes are offline-tested, not live-validated in the client applications.

The pi exporter rejects the native Codex auth profile: pi's standard Chat Completions adapter sends output-token limits, which that profile forbids. Use a Codex CLI-profile, Copilot, or Anthropic route for pi. Changing metadata does not relax native protocol restrictions.

Claude Code uses the native Anthropic model response, which this proxy forwards unchanged, including `max_input_tokens`, `max_tokens`, and `capabilities` when returned. Requests with `anthropic-version` or `x-api-key` select that native format; use Bearer authorization without those headers to fetch the extended OpenAI format. Native discovery continues to list Anthropic models only. For Copilot, use the explicit model setup below; these OpenAI metadata extensions do not add Copilot models to Claude Code's native picker. Claude Code feature detection remains subject to its [gateway and model configuration](https://code.claude.com/docs/en/model-config).

### Claude Code through Copilot

Start the proxy with your Copilot login:

```bash
java -jar AIProxy-5.1.jar serve --provider copilot
```

Choose a `copilot/<model-id>` from `/v1/models` whose account catalog advertises `/v1/messages`. Listing a model does not guarantee Messages support: unsupported models return HTTP 400. This route accepts any model advertising that endpoint, regardless of its name; models offering only Chat Completions or Responses cannot be used here. Claude Code was user-tested successfully with Sonnet and Haiku, including Haiku tool use and continuation. See [validation details](COMPATIBILITY.md#copilot-30).

In a separate PowerShell terminal, replace `copilot/MODEL_ID` with that qualified ID:

```powershell
$env:ANTHROPIC_BASE_URL = "http://127.0.0.1:10531"
$env:ANTHROPIC_AUTH_TOKEN = "YOUR_PROXY_KEY"
$env:ANTHROPIC_MODEL = "copilot/MODEL_ID"
$env:CLAUDE_CODE_EFFORT_LEVEL = "auto"
$env:ANTHROPIC_DEFAULT_HAIKU_MODEL = $env:ANTHROPIC_MODEL
$env:ANTHROPIC_DEFAULT_SONNET_MODEL = $env:ANTHROPIC_MODEL
$env:ANTHROPIC_DEFAULT_OPUS_MODEL = $env:ANTHROPIC_MODEL
$env:CLAUDE_CODE_SUBAGENT_MODEL = $env:ANTHROPIC_MODEL
claude
```

Use your proxy key, or a placeholder such as `local-proxy` when the proxy runs in open mode. Keep the base URL without `/v1`. Pinning the auxiliary models keeps those requests on Copilot too. Use effort `auto` for Haiku: an explicit `medium` effort caused an upstream 400 in testing. Claude Code may display unrecognized-model and auto-mode classifier billing notices; these did not block the tested requests. See Claude Code's [gateway setup](https://code.claude.com/docs/en/llm-gateway) and [model configuration](https://code.claude.com/docs/en/model-config).

Use explicit model IDs: the native Anthropic model picker does not list Copilot models, and `/v1/messages/count_tokens` is not supported. Unqualified names and `anthropic/<model-id>` still use Anthropic; Copilot Messages requests never fail over to another provider or protocol.

## Select providers

```bash
java -jar AIProxy-5.1.jar serve --provider copilot,codex
java -jar AIProxy-5.1.jar serve --provider all
java -jar AIProxy-5.1.jar serve --provider-order codex,copilot,anthropic
```

- `auto` enables providers with available credentials.
- `all` requires all three providers; `both` means Codex and Anthropic.
- `--provider-order` changes preference without enabling additional providers. The default order is Copilot, Codex, Anthropic.
- `--default-provider codex` sets an explicit preference among enabled providers. Use qualified model IDs when you need a particular provider.

To allow retries through another provider offering the same model, add `--failover`. It is disabled by default, applies only to eligible unqualified requests, and never substitutes a different model. Qualified requests and conversation continuations stay with their selected provider.

Use `--copilot-models`, `--codex-models`, or `--anthropic-models` with comma-separated IDs to restrict or configure model choices. Copilot and native Codex accept only models listed for your account. CLI Codex and Anthropic treat these lists as explicit overrides.

## Save your configuration

Create `production.yaml` with the settings you need:

```yaml
server:
  host: 127.0.0.1
  port: 10531

routing:
  provider: auto

startup:
  check: credentials
```

```bash
java -jar AIProxy-5.1.jar serve --config production.yaml
java -jar AIProxy-5.1.jar config show --config production.yaml
```

YAML is loaded only when you pass `--config`. Paths inside it are relative to the YAML file's directory. For more settings, copy [aiproxy.example.yaml](aiproxy.example.yaml) or run:

```bash
java -jar AIProxy-5.1.jar serve --help
```

Command-line options override environment variables, which override YAML settings. For example, `--port 8080`, `AIPROXY_PORT=8080`, and `server.port: 8080` set the same option at different priorities.

Common environment variables include `AIPROXY_PROVIDER`, `AIPROXY_HOST`, `AIPROXY_PORT`, `AIPROXY_STARTUP_CHECK`, and `AIPROXY_CLIENT_KEYS_FILE`. Provider settings use `AIPROXY_COPILOT_*`, `AIPROXY_CODEX_*`, or `AIPROXY_ANTHROPIC_*` names. Use `config show` to check effective settings and their sources without printing secrets.

Store proxy client keys in files or environment variables, not inline in YAML. Custom provider and OAuth URLs must use HTTPS, except for local development addresses.

## Set up client authentication

By default, the proxy accepts requests without a client API key. To require one, follow these steps after logging in to a provider. Proxy client keys are separate from your Copilot, Codex, or Anthropic credentials.

1. **Create a client key file.** Run these commands from the directory containing the downloaded JAR.

   macOS/Linux (Bash):

   ```bash
   java -jar AIProxy-5.1.jar key generate myapp > keys.txt
   ```

   Windows PowerShell:

   ```powershell
   java -jar AIProxy-5.1.jar key generate myapp | Set-Content -Encoding ascii keys.txt
   ```

   The file contains one entry such as `myapp:sk-proxy-<32 hex characters>`. Use the generated value, not this placeholder. Keep this file private. These commands replace `keys.txt`; to add another client, append with `>> keys.txt` in Bash or `Add-Content -Encoding ascii keys.txt` in PowerShell. Use one `name:key` or bare key per line.

2. **Start the proxy with key enforcement enabled.** Stop any existing proxy instance first.

   ```bash
   java -jar AIProxy-5.1.jar serve --client-keys-file keys.txt
   ```

3. **Configure the client application.**

   | Setting | Value |
   |---|---|
   | Base URL | `http://127.0.0.1:10531/v1` |
   | API key | The `sk-proxy-...` portion from `keys.txt`, without the `myapp:` prefix |
   | Model | An ID from `/v1/models` or the startup output |

   Replace the quick-start placeholder API key (`local`) with this generated key.

4. **Verify authentication.** In a second terminal, replace `YOUR_GENERATED_KEY` below with the bare key:

   ```bash
   curl -i http://127.0.0.1:10531/v1/models -H "Authorization: Bearer YOUR_GENERATED_KEY"
   curl -i http://127.0.0.1:10531/v1/models
   ```

   In PowerShell, use `curl.exe` instead of `curl`. The authenticated request should return HTTP 200; the request without a key should return HTTP 401. `/health` remains accessible without authentication.

For YAML configuration, add this to your configuration file and start with `serve --config production.yaml`. The key-file path is relative to the YAML file:

```yaml
client_auth:
  keys_file: ./keys.txt
```

### Optional admin key

Ordinary client keys can view their own `/v1/usage` statistics. An admin key can view usage for all keys. Generate a separate bare key with `java -jar AIProxy-5.1.jar key generate` and save it in `admin-key.txt` using the same redirection or PowerShell encoding shown above. Then start with:

```bash
java -jar AIProxy-5.1.jar serve --client-keys-file keys.txt --admin-client-key-file admin-key.txt
```

Send the admin key as a Bearer token when querying `/v1/usage`; give ordinary applications their own client keys.

### Allow network access

To accept connections from other machines, add `--host 0.0.0.0`. Network binding requires proxy API keys. Clients must use the proxy machine's address instead of `127.0.0.1`.

For browser applications, allow their origin with `--cors-origin https://your-app.example`. Origins must not include paths. `--allow-any-cors` requires proxy API keys.

## Check startup and troubleshoot

```bash
java -jar AIProxy-5.1.jar doctor
java -jar AIProxy-5.1.jar doctor --inference
```

Add `--config FILE` when using a configuration file. `doctor` checks credentials and model availability; `--inference` also sends test prompts. A failed check or degraded model catalog produces a nonzero exit code. If diagnostics report an Anthropic credential file is already in use, stop the process using that file before retrying.

Choose a startup check with `--startup-check`:

| Mode | What to expect |
|---|---|
| `inference` (default) | Sends test prompts and reports the successful model |
| `credentials` | Checks credentials without sending test prompts |
| `off` | Skips the check; credential loading and model discovery still occur |

Inference checks choose the first model ID containing `luna` or `haiku` (case-insensitive), otherwise the last model in the list. Copilot and Codex can try other models after a failure, up to six in total. Anthropic tests only the selected model.

The startup `Auth` line shows the selected credential source and profile. Use `Check` to see whether the chosen test passed. The `Models` line helps diagnose availability:

| Model status | What to do |
|---|---|
| `discovered`, `cache` | Use the listed model IDs |
| `configured` | Check your model overrides if requests fail |
| `stale cache` | Read the discovery warning; the list could not be refreshed |
| `fallback (built-in)` | Anthropic discovery failed; the built-in list does not confirm account access |
| `unavailable` | Resolve the reported credential or discovery error before using that provider |

`Ready with warnings` means the proxy is running but one or more checks or model lookups need attention.

| Problem | Next step |
|---|---|
| No providers enabled | Log in to a provider, then run `auth status` |
| Wrong Codex login selected | Set `--codex-auth-mode native` or `cli`, and inspect `config show` |
| Invalid credential file | Correct the selected file or log in again; an invalid explicit file does not fall back to another login |
| Copilot says "model not available for integrator" | Try another listed model. If using a custom OAuth client ID, check `copilot.oauth_client_id` and log in again after changing it |
| HTTP 400 in native Codex mode | Remove unsupported request options; see the native-mode restrictions above |
| Unknown model or ambiguous provider | Copy an ID from `/v1/models` and qualify the provider if needed |
| Option rejected | Put options after the final subcommand and check that command's `--help` |

For request diagnostics, start with `--log-requests --request-log-dir ./logs/requests`. Logs can contain prompts, responses, and tool output even though credentials are redacted; protect the directory.

## Further documentation

To build from source instead, install Java 21 and Maven, clone this repository, and run `mvn clean package` from its root. The JAR is written to `target/AIProxy-5.1.jar`; run the examples above with that path.

- [Example configuration](aiproxy.example.yaml)
- [API compatibility](COMPATIBILITY.md)
- [Developer guide](DEVELOPER_GUIDE.md)
- [Manual test plan](MANUAL_TEST_PLAN.md)

### Source organization

The Java launcher is `ProxyApplication`. CLI commands live in `cli`, startup/assembly in `bootstrap`, HTTP dispatch in `server`, and each provider owns its backends, authentication and catalog under `provider/<name>`. Shared wire-format code lives in `protocol`, with common model, transport, SSE and replay infrastructure in dedicated packages. See [the developer guide](DEVELOPER_GUIDE.md#project-structure) for package boundaries and naming rules.
