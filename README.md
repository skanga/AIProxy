# AIProxyOauth 4.0

Use GitHub Copilot, ChatGPT/Codex, and Anthropic accounts from applications that support an OpenAI-compatible API. Anthropic clients can also connect through the native Messages API.

Provider credentials stay on the machine running the proxy. Client applications connect to the proxy's local URL and can use separate proxy API keys.

## Quick start

You need **Java 21** and access to at least one supported provider.

1. Download the JAR from [GitHub Releases](https://github.com/skanga/AIProxyOauth/releases). Choose the `AIProxyOauth-<version>.jar` asset, then open a terminal in the folder where you saved it. The examples below use `AIProxyOauth-4.0.jar`; substitute your downloaded filename if different.

2. Log in to a provider. Choose one:

   ```bash
   java -jar AIProxyOauth-4.0.jar auth copilot login
   java -jar AIProxyOauth-4.0.jar auth codex login
   java -jar AIProxyOauth-4.0.jar auth anthropic login
   ```

3. Start the proxy:

   ```bash
   java -jar AIProxyOauth-4.0.jar
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
java -jar AIProxyOauth-4.0.jar serve --startup-check credentials
```

Stop the proxy with **Ctrl+C**.

## Log in and manage accounts

Check your saved authentication settings with:

```bash
java -jar AIProxyOauth-4.0.jar auth status
```

Use the same `--config FILE` for login and serving if you customize credential locations. Place options after the final subcommand, for example `auth anthropic login --config production.yaml` or `serve --config production.yaml`.

### GitHub Copilot

```bash
java -jar AIProxyOauth-4.0.jar auth copilot login
java -jar AIProxyOauth-4.0.jar serve --provider copilot
```

Follow the printed device-login instructions. If your login expires, run the login command again.

To supply an existing token, set `AIPROXY_COPILOT_TOKEN` or use `--copilot-token-file PATH`. The file can contain a raw token, a proxy credential file, or a Copilot CLI JSONC configuration. An explicit file takes precedence over the environment token and saved login.

For GitHub Enterprise Cloud, use `--copilot-github-host tenant.ghe.com` for both login and serving, with separate credentials. Enterprise Cloud has not been live-validated; GitHub Enterprise Server is unsupported.

To remove the proxy's saved login:

```bash
java -jar AIProxyOauth-4.0.jar auth copilot logout
```

This leaves explicitly supplied token files untouched.

### ChatGPT / Codex

For the proxy's native ChatGPT login:

```bash
java -jar AIProxyOauth-4.0.jar auth codex login
java -jar AIProxyOauth-4.0.jar serve --provider codex --codex-auth-mode native
```

Complete sign-in in a browser on the same computer. Use `--no-browser` to print the URL instead of opening it automatically. Account access to native sign-in is required.

If you already use the official Codex CLI, you can use its saved login instead:

```bash
java -jar AIProxyOauth-4.0.jar serve --provider codex --codex-auth-mode cli
```

For device login, including from a headless machine, install the official Codex CLI on `PATH`, then run:

```bash
java -jar AIProxyOauth-4.0.jar auth codex login --device-auth
java -jar AIProxyOauth-4.0.jar serve --provider codex --codex-auth-mode cli
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
java -jar AIProxyOauth-4.0.jar auth codex logout --yes
```

CLI credentials are separate. Remove them with the official `codex logout`, using the same `CODEX_HOME` and credential-storage settings used for login. In `auto` mode, a remaining CLI login may be selected after native logout.

Native mode has request restrictions: omit temperature, top-p, token-limit, metadata, and background-execution options. Unsupported options return HTTP 400. Use the default native endpoint and settings; CLI endpoint overrides, `--codex-store`, and prompt-cache header forwarding are incompatible with native mode.

### Anthropic

```bash
java -jar AIProxyOauth-4.0.jar auth anthropic login
java -jar AIProxyOauth-4.0.jar serve --provider anthropic
```

For SSH or a headless machine:

```bash
java -jar AIProxyOauth-4.0.jar auth anthropic login --no-browser
```

Open the printed URL on another device and paste the returned `code#state` into the original terminal. Without an interactive console, add `--allow-stdin-oauth-code`.

You can also supply `CLAUDE_CODE_OAUTH_TOKEN`. To customize the saved login location, use `--anthropic-oauth-file PATH` for both login and serving.

```bash
java -jar AIProxyOauth-4.0.jar auth anthropic logout
```

## Connect a client and choose a model

List the models available through the running proxy:

```bash
curl http://127.0.0.1:10531/v1/models
```

In Windows PowerShell, use `curl.exe` if `curl` refers to a PowerShell alias. If proxy authentication is enabled, add `-H "Authorization: Bearer YOUR_PROXY_KEY"`.

Copy a returned model ID into your client. Copilot IDs include `copilot/`. You can explicitly select another provider with `codex/<model-id>` or `anthropic/<model-id>`. Qualify the model when multiple providers offer the same ID.

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

### Claude Code through Copilot

Start the proxy with your Copilot login:

```bash
java -jar AIProxyOauth-4.0.jar serve --provider copilot
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
java -jar AIProxyOauth-4.0.jar serve --provider copilot,codex
java -jar AIProxyOauth-4.0.jar serve --provider all
java -jar AIProxyOauth-4.0.jar serve --provider-order codex,copilot,anthropic
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
java -jar AIProxyOauth-4.0.jar serve --config production.yaml
java -jar AIProxyOauth-4.0.jar config show --config production.yaml
```

YAML is loaded only when you pass `--config`. Paths inside it are relative to the YAML file's directory. For more settings, copy [aiproxy.example.yaml](aiproxy.example.yaml) or run:

```bash
java -jar AIProxyOauth-4.0.jar serve --help
```

Command-line options override environment variables, which override YAML settings. For example, `--port 8080`, `AIPROXY_PORT=8080`, and `server.port: 8080` set the same option at different priorities.

Common environment variables include `AIPROXY_PROVIDER`, `AIPROXY_HOST`, `AIPROXY_PORT`, `AIPROXY_STARTUP_CHECK`, and `AIPROXY_CLIENT_KEYS_FILE`. Provider settings use `AIPROXY_COPILOT_*`, `AIPROXY_CODEX_*`, or `AIPROXY_ANTHROPIC_*` names. Use `config show` to check effective settings and their sources without printing secrets.

Store proxy client keys in files or environment variables, not inline in YAML. Custom provider and OAuth URLs must use HTTPS, except for local development addresses.

## Require API keys or allow network access

Generate a client key and an optional admin key:

```bash
java -jar AIProxyOauth-4.0.jar key generate myapp
java -jar AIProxyOauth-4.0.jar key generate
```

Save the first command's `myapp:sk-proxy-...` output in `keys.txt`. Save the second command's bare key in `admin-key.txt`. You can add more client keys to `keys.txt`, one `name:key` or bare key per line.

```bash
java -jar AIProxyOauth-4.0.jar serve --client-keys-file keys.txt --admin-client-key-file admin-key.txt
```

Set each client's API key to its generated key. `/v1/usage` shows that key's usage; the admin key can view all keys' usage. `/health` does not require authentication.

To accept connections from other machines, add `--host 0.0.0.0`. Network binding requires proxy API keys. Clients must use the proxy machine's address instead of `127.0.0.1`.

For browser applications, allow their origin with `--cors-origin https://your-app.example`. Origins must not include paths. `--allow-any-cors` requires proxy API keys.

## Check startup and troubleshoot

```bash
java -jar AIProxyOauth-4.0.jar doctor
java -jar AIProxyOauth-4.0.jar doctor --inference
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

To build from source instead, install Java 21 and Maven, clone this repository, and run `mvn clean package` from its root. The JAR is written to `target/AIProxyOauth-4.0.jar`; run the examples above with that path.

- [Example configuration](aiproxy.example.yaml)
- [API compatibility](COMPATIBILITY.md)
- [Developer guide](DEVELOPER_GUIDE.md)
- [Manual test plan](MANUAL_TEST_PLAN.md)
