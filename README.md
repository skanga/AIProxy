# AIProxyOauth 3.0

AIProxyOauth is a Java 21 OAuth proxy exposing OpenAI-compatible and Anthropic-compatible APIs. It routes requests to the `copilot`, `codex`, and `anthropic` upstream providers while keeping provider credentials on the proxy host.

## Build and run

```bash
mvn clean package
java -jar target/AIProxyOauth-3.1.jar
```

With no arguments, the proxy starts on `127.0.0.1:10531`, enables every provider with usable credentials, uses the provider order Copilot, Codex, Anthropic, and performs one minimal inference check per enabled provider. Exact model matches take priority; collisions require qualification unless failover is enabled. `serve` is optional:

```bash
java -jar target/AIProxyOauth-3.1.jar serve --startup-check off
```

Place options after the final subcommand: `aiproxy serve --config production.yaml`.
Options before a subcommand, such as `aiproxy --config production.yaml serve`, are rejected.
Without a subcommand, `aiproxy --config production.yaml` still starts the proxy.

Codex supports native Sign in with ChatGPT and existing Codex CLI credentials. Anthropic credentials can be created and inspected with:

```bash
java -jar target/AIProxyOauth-3.1.jar auth anthropic login
java -jar target/AIProxyOauth-3.1.jar auth status
java -jar target/AIProxyOauth-3.1.jar auth anthropic logout
```

`CLAUDE_CODE_OAUTH_TOKEN` remains supported for Claude Code interoperability.

Anthropic login and logout accept `--config <yaml>` and `--anthropic-oauth-file <path>`.
They resolve the credential file using CLI > `AIPROXY_ANTHROPIC_OAUTH_FILE` > YAML > default,
including YAML-relative paths and home-directory expansion.

For Anthropic login from SSH or another headless machine, use `auth anthropic login --no-browser`.
Open the printed URL in a browser on another device and paste the returned `code#state` into the original terminal.
If there is no interactive console, add `--allow-stdin-oauth-code` explicitly. This uses the existing
browser authorization-code flow, not a device-code grant. See [Anthropic authentication](https://code.claude.com/docs/en/authentication).

### Native OpenAI / ChatGPT login

```bash
java -jar target/AIProxyOauth-3.1.jar auth codex login
java -jar target/AIProxyOauth-3.1.jar serve --provider codex --codex-auth-mode native
java -jar target/AIProxyOauth-3.1.jar auth status
java -jar target/AIProxyOauth-3.1.jar auth codex logout --yes
```

Login uses the [documented Sign in with ChatGPT flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in): dynamic client registration, PKCE, state and nonce, a temporary `127.0.0.1` callback listener, and cryptographically verified ID tokens. `--no-browser` prints the URL for opening on the same computer. The callback expires after ten minutes. Availability depends on OpenAI account access to this flow.

For device-code login, install the official Codex CLI on PATH and run:

```bash
java -jar target/AIProxyOauth-3.1.jar auth codex login --device-auth
java -jar target/AIProxyOauth-3.1.jar serve --provider codex --codex-auth-mode cli
```

This delegates to [official Codex device authorization](https://learn.chatgpt.com/docs/auth), including polling, expiry and account requirements.
Enable device-code login in ChatGPT security settings or through your workspace administrator if required.
It writes **CLI-profile** credentials and preserves the separate native login. The destination follows
`--codex-oauth-file` > `AIPROXY_CODEX_OAUTH_FILE` > YAML `codex.oauth_file` > `CODEX_HOME/auth.json` > `~/.codex/auth.json`.
Explicit destinations must be named `auth.json`; the CLI runs with their parent as `CODEX_HOME` and file-based storage enforced.
Existing CLI credentials at that destination may be replaced by the official CLI. Device login cannot be combined with
`--no-browser`, `--new-account`, or `--codex-native-auth-file`. It does not change serving settings; select CLI mode and
the same explicit OAuth file when starting the proxy. Native `auth codex logout` leaves these CLI credentials in place;
use official `codex logout` with the same `CODEX_HOME` and file-storage configuration to remove them.

Both commands accept `--config FILE` and `--codex-native-auth-file PATH`. The native file defaults to `codex-auth.json` beside the proxy's managed Anthropic credential file (Windows: `%LOCALAPPDATA%/AIProxyOauth`; other systems: `$XDG_CONFIG_HOME/AIProxyOauth` or `~/.config/AIProxyOauth`). It is separate from the official CLI's `auth.json`. Files use owner-only permissions, atomic replacement, and a cross-process lock for rotating refresh tokens. Failed login leaves the active credential unchanged. Filesystems without atomic replacement or enforceable permissions fail closed.

`codex.auth_mode` / `AIPROXY_CODEX_AUTH_MODE` / `--codex-auth-mode` accepts:

| Mode | Credential selection |
|---|---|
| `auto` (default) | Explicit `codex.oauth_file`, then an existing native file, then `CODEX_HOME/auth.json` or `~/.codex/auth.json` |
| `native` | Only `codex.native_auth_file`; an explicit CLI OAuth file conflicts |
| `cli` | Explicit CLI file or existing Codex CLI discovery; use official `codex login` for initial authentication |

An invalid selected file never falls through to another source. `config show` includes the selected profile, file, and effective inference URL; `auth status` includes native account/client identity and expiry, without tokens. `doctor` validates credentials and model discovery; `doctor --inference` also checks a request.

Login normally reuses the saved account registration. Use `auth codex login --new-account` to register another account and replace the active login after successful verification. Prior account/client mappings are retained without tokens; this release has one active account per native file, not an account picker. Restart running proxies after login: a process pins its login identity to prevent conversation history or model caches from crossing accounts. Logout attempts remote session revocation, clears only native tokens, and retains the client/host registration for later sign-in. It reports if revocation was unconfirmed. `logout --yes` can clear a corrupt native file; external CLI files are never deleted, and auto mode may select them on the next start.

Native tokens are sent only to `https://api.openai.com/v1`, without legacy account/beta/cache headers or redirects. Native mode rejects custom CLI endpoints/client IDs/version settings, `codex.store: true`, and prompt-cache header forwarding. Its models come from the account catalog; `codex.models` filters that list. Existing CLI mode keeps its original backend and behavior.

The native profile follows OpenAI's [preview request limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations). It streams upstream with `store: false`, while supporting both streaming and collected client responses. Chat messages and string Responses input are translated to array input; flat function/custom tools become `additional_tools` input items. Unsupported fields (including token limits, temperature, top-p, metadata, and background execution) and unsupported hosted tools return 400 instead of being silently dropped. Chat Completions supports text/images and function tools; Responses additionally accepts supported file/custom-tool inputs. `previous_response_id` and item references work only while history remains in the same process and client namespace; missing history returns 400 and requires full input. History is not persisted across restarts.

The native protocol is a preview and may change. Automated tests use simulated OpenAI responses; live account login and inference require an interactive smoke test.

### GitHub Copilot

```bash
java -jar target/AIProxyOauth-3.1.jar auth copilot login
java -jar target/AIProxyOauth-3.1.jar serve --provider copilot
java -jar target/AIProxyOauth-3.1.jar auth copilot logout
```

Login uses GitHub device authorization with no repository scope. Credentials are stored in the proxy's `copilot-auth.json`, alongside its Anthropic credential file, with owner-only permissions. Logout removes only that managed login. Expired credentials require login again; explicitly supplied token files are reread so their owner can rotate them.

Alternatively set `AIPROXY_COPILOT_TOKEN`, or pass `--copilot-token-file PATH`. An explicit file may contain a raw token, a proxy-managed credential document, or a read-only Copilot CLI JSONC config. File credentials take precedence over the environment token, then the managed login. Invalid explicit credentials never fall back to another source. The proxy does not search other applications' credential stores.

> **Copilot model access depends on the OAuth app.** GitHub ties each Copilot token to an "integrator" (determined by the `oauth_client_id` it was minted under), and the integrator decides which models the token may call for chat. The proxy defaults to the ecosystem-standard Copilot device-flow client id (`Iv1.b507a08c87ecfe98`), which exposes the full model catalog. If you see a small allow-list or a startup "model not available for integrator" error, your token was minted under a more restrictive app — set `AIPROXY_COPILOT_OAUTH_CLIENT_ID` (or `copilot.oauth_client_id`) and run `auth copilot login` again to re-mint it.

Select models returned by `/v1/models`, such as `copilot/gpt-4o-mini` **only if your account lists it**. Copilot model lists restrict discovery; they cannot add unavailable models. Catalogs are fresh for five minutes and may be reused during an outage for up to one hour from the last successful fetch. There is no built-in Copilot model fallback.

GitHub.com is live-tested. GitHub Enterprise Cloud can be selected with `--copilot-github-host tenant.ghe.com` and separate credentials; it is **not live-validated**. On-premises GitHub Enterprise Server is unsupported.

### Routing and migration from 2.0

`--provider auto` now includes Copilot when credentials are available. `--provider all` requires all three providers; `both` still means Codex and Anthropic. Explicit lists such as `--provider copilot,codex` are supported. `--provider-order codex,copilot,anthropic` changes preference without enabling providers. Omitted providers are appended in default order. An explicit enabled `--default-provider` takes precedence.

`--failover` is off by default. When enabled, an unqualified request can try enabled providers advertising the **same raw model ID** and the requested capabilities, in provider order. The eligible default provider is tried first. Unknown capabilities are not assumed: older catalogs may exclude image, reasoning, or tool requests from failover. Only connection/timeouts, HTTP 429, and HTTP 5xx can trigger another attempt, before any response is committed. Authentication, invalid input, qualified model names, and stateful continuations never fail over. No model substitution occurs.

Startup always lists every known available model beneath its provider. Both the provider summary and the provider sections follow the configured provider order.

Copilot Responses continuations use a bounded local cache, isolated by client key and Copilot credential identity. Only completed responses are cached. Missing, evicted, or other-client references fail explicitly; restart or credential rotation invalidates replay. Continuations remain on their original provider.

## Client APIs and providers

“OpenAI-compatible” and “Anthropic-compatible” describe the protocols clients use. They are not upstream provider names.

| Client API | Endpoints | Routing |
|---|---|---|
| OpenAI-compatible | `/v1/chat/completions`, `/v1/responses`, `/v1/models` | Qualified names pin a provider; discovery, model-family routing, and the default resolve unqualified names |
| Anthropic-compatible | `/v1/messages` | Always uses the Anthropic provider |

Provider identifiers are `copilot`, `codex`, and `anthropic`. Qualify an OpenAI-compatible model as `copilot/<discovered-id>`, `codex/gpt-5.5`, or `anthropic/claude-sonnet-4-5` when routing must be explicit. Copilot model listings always include the `copilot/` prefix; existing providers keep their listing format.

## Commands

```text
aiproxy [serve] [options]
aiproxy auth anthropic login [options]
aiproxy auth anthropic logout [options]
aiproxy auth copilot login [options]
aiproxy auth copilot logout [options]
aiproxy auth status [--config <yaml>]
aiproxy key generate [name]
aiproxy config show [--config <yaml>]
aiproxy doctor [--config <yaml>] [--inference]
```

`config show` prints the resolved value and source for each setting, including explicit provider-list membership. OAuth tokens, proxy keys, and admin keys are never printed.

`doctor` validates configuration, credentials, and model discovery without opening a server listener.
`doctor --inference` also runs inference checks through a temporary loopback listener on an available port;
it does not bind the configured serving port. Diagnostic listeners are closed after success or failure.
Credential refresh locking still applies: if an Anthropic credential file is already in use by another process,
diagnostics report that error and exit nonzero rather than bypassing the lock.

### Serve options

```text
General:
  --config <yaml>
  --host <address>
  --port <port>
  --provider <auto|all|both|copilot|codex|anthropic|comma-separated-list>
  --default-provider <copilot|codex|anthropic>
  --provider-order <comma-separated-list>
  --failover / --no-failover
  --startup-check <off|credentials|inference>

Proxy client authentication:
  --client-keys-file <path>
  --admin-client-key-file <path>

CORS and logging:
  --cors-origin <origin>       repeatable or comma-separated
  --allow-any-cors
  --log-requests
  --request-log-dir <path>

Codex:
  --codex-auth-mode <auto|native|cli>
  --codex-native-auth-file <path>
  --codex-models <ids>
  --codex-version <version>
  --codex-base-url <url>
  --codex-oauth-file <path>
  --codex-oauth-client-id <id>
  --codex-oauth-token-url <url>
  --codex-store
  --codex-forward-prompt-cache-headers
  --codex-instructions-mode <none|file|latest>
  --codex-instructions-file <path>
  --codex-instructions-cache-dir <path>

Anthropic:
  --anthropic-models <ids>
  --anthropic-base-url <url>
  --anthropic-oauth-file <path>
  --anthropic-token-url <url|default>

Copilot:
  --copilot-github-host <github.com|tenant.ghe.com>
  --copilot-oauth-file <path>
  --copilot-oauth-client-id <id>
  --copilot-token-file <path>
  --copilot-models <discovered-ids>
```

CLI Codex and Anthropic model lists are explicit overrides. Native Codex and Copilot lists filter discovered account models. When omitted, each provider's normal discovery and caching behavior is used.

## YAML configuration

YAML is loaded only when explicitly selected with `--config`. Relative paths are resolved from the YAML file’s directory.

A ready-to-copy configuration is available in [`aiproxy.example.yaml`](aiproxy.example.yaml).

```yaml
server:
  host: 127.0.0.1
  port: 10531

routing:
  provider: auto
  provider_order: [copilot, codex, anthropic]
  failover: false
  # default_provider: codex  # Optional explicit preference; must be enabled.

client_auth:
  keys_file: ./keys.txt
  admin_key_file: ./admin-key.txt

codex:
  auth_mode: auto
  # native_auth_file: ~/.aiproxy/codex-auth.json
  # oauth_file: ~/.codex/auth.json  # Explicitly selects CLI credentials in auto mode.
  models: []
  version: null
  base_url: https://chatgpt.com/backend-api/codex
  oauth_client_id: app_EMoamEEZ73f0CkXaXp7hrann
  oauth_token_url: null
  store: false
  forward_prompt_cache_headers: false
  instructions:
    mode: none
    file: null
    cache_dir: ./cache/codex-instructions

copilot:
  github_host: github.com
  oauth_file: ~/.aiproxy/copilot-auth.json
  oauth_client_id: Iv1.b507a08c87ecfe98
  token_file: null
  models: []

anthropic:
  oauth_file: ~/.aiproxy/anthropic-auth.json
  models: []
  base_url: https://api.anthropic.com
  token_url: default

cors:
  origins: []
  allow_any: false

logging:
  requests: false
  directory: ./logs/requests

startup:
  check: inference
```

Unknown keys, malformed values, unreadable required files, conflicting instruction settings, and inline client secrets fail validation. Client and admin keys must come from files or environment variables.

YAML sections must be objects; use `{}` for an empty section. Lists contain strings, boolean settings
accept booleans or `"true"`/`"false"`, and the port accepts an integer or integer string.
Comma-separated strings remain supported for list settings. Null leaf values retain defaults.

An instruction mode of `none` or `latest` discards an instruction file inherited from a lower-precedence
source. A file explicitly supplied at the same or higher precedence remains a configuration error.

## Precedence and environment variables

Configuration precedence is:

```text
CLI > AIPROXY_* environment > YAML > ecosystem credential discovery > defaults
```

Every serve option has a corresponding uppercase environment variable, including:

```text
AIPROXY_HOST
AIPROXY_PORT
AIPROXY_PROVIDER
AIPROXY_DEFAULT_PROVIDER
AIPROXY_PROVIDER_ORDER
AIPROXY_FAILOVER
AIPROXY_COPILOT_GITHUB_HOST
AIPROXY_COPILOT_OAUTH_FILE
AIPROXY_COPILOT_OAUTH_CLIENT_ID
AIPROXY_COPILOT_TOKEN_FILE
AIPROXY_COPILOT_TOKEN
AIPROXY_COPILOT_MODELS
AIPROXY_STARTUP_CHECK
AIPROXY_CLIENT_KEYS_FILE
AIPROXY_ADMIN_CLIENT_KEY_FILE
AIPROXY_CORS_ORIGINS
AIPROXY_ALLOW_ANY_CORS
AIPROXY_LOG_REQUESTS
AIPROXY_REQUEST_LOG_DIR
AIPROXY_CODEX_MODELS
AIPROXY_CODEX_VERSION
AIPROXY_CODEX_BASE_URL
AIPROXY_CODEX_OAUTH_FILE
AIPROXY_CODEX_AUTH_MODE
AIPROXY_CODEX_NATIVE_AUTH_FILE
AIPROXY_CODEX_OAUTH_CLIENT_ID
AIPROXY_CODEX_OAUTH_TOKEN_URL
AIPROXY_CODEX_STORE
AIPROXY_CODEX_FORWARD_PROMPT_CACHE_HEADERS
AIPROXY_CODEX_INSTRUCTIONS_MODE
AIPROXY_CODEX_INSTRUCTIONS_FILE
AIPROXY_CODEX_INSTRUCTIONS_CACHE_DIR
AIPROXY_ANTHROPIC_MODELS
AIPROXY_ANTHROPIC_BASE_URL
AIPROXY_ANTHROPIC_OAUTH_FILE
AIPROXY_ANTHROPIC_TOKEN_URL
```

Secrets use only:

```text
AIPROXY_CLIENT_KEYS=name:sk-proxy-...,other:sk-proxy-...
AIPROXY_ADMIN_CLIENT_KEY=sk-proxy-...
```

`CODEX_HOME` and `CLAUDE_CODE_OAUTH_TOKEN` remain ecosystem discovery inputs. The old `CHATGPT_LOCAL_*` variables are no longer read.

## Startup checks

| Mode | Behavior |
|---|---|
| `off` | Sends no inference requests. Credential/model discovery still occurs and each provider displays `Check: skipped`. |
| `credentials` | Loads credentials, refreshes when needed, and validates local token/account metadata without a model prompt. |
| `inference` | After the server starts, calls `/v1/chat/completions` for Codex and `/v1/messages` for Anthropic. This is the default. |

Inference failures are nonfatal for `serve`: warnings are grouped at the end and the proxy reports `Ready with warnings.` Diagnostic text is bounded, sanitized, and redacted. Use `doctor --inference` in automation when a failed check must produce a nonzero exit code.

## Client authentication and CORS

Generate keys with:

```bash
java -jar target/AIProxyOauth-3.1.jar key generate cursor
```

A keys file contains one `name:key` or bare `key` per line. The admin key is stored in a separate file. `/health` remains unauthenticated; protected OpenAI-compatible endpoints accept `Authorization: Bearer <proxy-key>`, and Anthropic-compatible endpoints also accept `x-api-key`.

Binding to a non-loopback host requires client authentication. Wildcard CORS also requires client authentication. Explicit CORS origins must be HTTP/HTTPS origins without paths, queries, fragments, or user information.

## URL rules

Provider bases and OAuth token URLs must use HTTPS. Plain HTTP is accepted only for explicit loopback development URLs such as `http://127.0.0.1:8081` or `http://localhost:8082/v1`.

Trailing slashes are removed. Anthropic bases may include `/v1`; it is normalized away so generated endpoints contain exactly one `/v1`.

## Request logging

`--log-requests` can store bounded request and response bodies as well as metadata. OAuth tokens, proxy client keys, authorization headers, and sensitive reasoning material are redacted, but prompts and tool output may still be sensitive. Protect and rotate the log directory.

## Migration from 1.x

This is a breaking release; removed flags are rejected with replacement guidance.

| 1.x | 2.0 |
|---|---|
| `--models` | `--codex-models` |
| `--base-url` | `--codex-base-url` |
| `--oauth-file` | `--codex-oauth-file` |
| `--oauth-client-id` | `--codex-oauth-client-id` |
| `--oauth-token-url` | `--codex-oauth-token-url` |
| `--providers` | `--provider auto|codex|anthropic|both` |
| `--store` | `--codex-store` |
| `--forward-prompt-cache-headers` | `--codex-forward-prompt-cache-headers` |
| `--codex-instructions configured|latest-codex` | `--codex-instructions-mode none|file|latest` |
| `--api-keys-file` | `--client-keys-file` |
| `--admin-key` | `--admin-client-key-file` or `AIPROXY_ADMIN_CLIENT_KEY` |
| `--api-key` | `AIPROXY_CLIENT_KEYS` |
| `--generate-key [name]` | `key generate [name]` |
| `--anthropic-login` | `auth anthropic login` |
| `--anthropic-logout` | `auth anthropic logout` |

## Development

```bash
mvn test
mvn clean package
mvn test -Dtest=ClassName
```

See [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md) for the release matrix.
