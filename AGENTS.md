## Build Commands

```bash
mvn clean package -DskipTests   # Build fat JAR → target/AIProxy-5.0.jar
mvn clean package               # Build with tests
mvn test                        # Run all tests
mvn test -Dtest=ClassName       # Run a single test class
mvn clean compile               # Compile only
```

**Run the proxy:**
```bash
java -jar target/AIProxy-5.0.jar --port 8080
java -jar target/AIProxy-5.0.jar key generate myapp   # Generate an API key
```

## Architecture Overview

AIProxy is a Java 21 proxy with Copilot, Codex, and Anthropic providers. OpenAI Chat Completions and Responses endpoints route across enabled providers. The native Anthropic Messages endpoint uses Anthropic for unqualified or `anthropic/<id>` models, and Copilot for explicit `copilot/<id>` models whose catalog advertises `/v1/messages`. Native Messages requests never fail over across providers or protocols.

**Request flow:**
```
ProxyApplication -> cli.ProxyCommand -> bootstrap.ProxyRuntime / ProviderAssembly
Client -> ProxyServer -> InferenceDispatchHandler (explicit Chat or Responses API)
       -> provider-specific backend -> provider-specific authenticated HTTP client
Native Messages -> MessagesDispatchHandler -> AnthropicMessagesBackend or CopilotMessagesBackend
```

**Package ownership:**

| Package | Responsibility |
|---------|----------------|
| root | `ProxyApplication` launcher |
| `cli` | picocli commands/options, configuration and startup rendering |
| `bootstrap` | `ProxyRuntime`, `ProviderAssembly`, provider startup selection and lifecycle |
| `server` | HTTP routes/middleware, `ProxyEndpoints`, dispatch, backend contracts, shared HTTP helpers |
| `routing` | Model routes, provider selection and routing errors |
| `model` | Shared model records, catalog contracts, aggregation and scalar validation |
| `provider.spi` | Shared request/event types, normalized request validation and provider errors; no HTTP dependency |
| `provider.codex` | Codex backends, HTTP client, instructions, native request policy; `auth` and `model` subpackages |
| `provider.anthropic` | Anthropic backends/client and OAuth compatibility/header policy; `auth` and `model` subpackages |
| `provider.copilot` | Copilot backends/client, endpoint selection and provider codecs; `auth` and `model` subpackages |
| `protocol.chat` / `protocol.responses` | Per-format request encoders/decoders, stream decoders, output encoders and Responses stream collection |
| `protocol.shared` | Shared inline-image parsing and completion stream event bookkeeping |
| `protocol.messages` | Shared Messages encoding/decoding, usage observation and beta-header syntax; no OAuth identity or forced-streaming policy |
| `auth`, `transport`, `sse`, `state` | Shared credential paths, bounded reads/transport utilities, SSE framing and replay mechanics |
| `logging`, `usage`, `util` | Logging, per-key accounting and small general utilities |

Provider implementations must not import another provider's implementation. Protocol/model/SSE/state/SPI code must not depend on provider implementations or HTTP server code. `PackageBoundariesTest` enforces these rules and package/path consistency. HTTP-aware backends implement `InferenceBackend` with an explicit `InferenceApi`; native Messages uses the separate `MessagesBackend` contract. Shared replay storage is instantiated per backend, preserving client/account namespaces and provider limits.

Use `Handler` for HTTP endpoints/dispatch, `Backend` for provider execution, `HttpClient` for authenticated transport and `ModelCatalog` for provider discovery. Mirror production packages in unit tests. Preserve the artifact/JAR name, external identifiers and credential locations when refactoring Java names.

## API Endpoints

- `GET /health` — liveness check
- `GET /v1/models` — model list (cached 5 minutes)
- `POST /v1/chat/completions` — main endpoint; translates to/from Responses API
- `POST /v1/responses` — routed Responses API
- `POST /v1/messages` — native Anthropic Messages protocol, routed to Anthropic or explicit Copilot
- `GET /v1/usage` — per-key usage stats (admin key sees all keys)

## Key Design Details

**Copilot:** Direct GitHub bearer authentication; credentials come from an explicitly configured file, dedicated environment token, or proxy-managed login, in that order. Never discover other applications' stores. No fabricated Copilot models. List IDs as `copilot/<id>`. Enterprise Cloud host isolation is offline-tested; live enterprise validation is optional and currently unverified.

**Routing:** Default order is Copilot, Codex, Anthropic. `both` retains its Codex/Anthropic meaning; `all` selects all three. Failover is disabled by default, requires exact raw model/capability matches, and stops after downstream response commitment. Qualified and replay requests never fail over. Preserve existing provider behavior and use behavioral RED/GREEN tests for changes.

**API Translation (`CodexChatBackend`):** Converts `messages[]` (system/user/assistant/tool roles), `tools`/`function_call`, `stream`, `reasoning_effort`, and token usage between the two formats. The Codex translation path remains independent of the shared normalized-event path.

**Streaming:** Each request runs on a virtual thread. SSE lines are parsed and forwarded in real time. For non-streaming requests, `ResponsesStreamCollector` buffers the SSE stream and emits a single JSON response.

**Auth refresh:** `CodexAuthManager` uses a `ReentrantLock` to serialize refreshes. Tokens are refreshed if they expire within 5 minutes or if more than 55 minutes have elapsed since last refresh. OAuth config is read from `auth.json`.

**Codex authentication profiles:** `auth codex login`/`logout` use the documented native Sign in with ChatGPT flow (dynamic registration, PKCE, state/nonce, verified ID token, IPv4 loopback callback). Native credentials live separately from official CLI auth.json and use only `https://api.openai.com/v1`; never send them to backend-api or attach legacy account/beta/cache headers. `codex.auth_mode` is auto/native/cli. Auto selects explicit CLI file > native file > CLI discovery; invalid selected files fail without fallback. Native refresh rotates under a cross-process lock and atomically persists owner-only files. Logout never deletes CLI credentials. Processes pin native session identity; restart after re-login to prevent cache/replay account mixing. Native request restrictions are explicit 400 errors, model overrides filter the account catalog, and replay is local/client-scoped. `--new-account` registers another account; only one active native file is supported. Native profile uses browser flow only. `auth codex login --device-auth` delegates to the official Codex CLI and writes CLI-profile auth.json using effective configuration. Anthropic `--no-browser` supports another-device browser authorization with pasted code#state, not a device-code grant. Preserve CLI behavior and treat native preview protocol changes as maintenance work requiring regression tests.

**Model discovery fallback chain:** local Codex CLI binary → NPM registry → hardcoded version string. Results are cached with double-checked locking.

**API key enforcement:** Optional. Keys follow the format `sk-proxy-<32 hex chars>`. An admin key is designated at startup for viewing all usage stats. By default the server runs in open mode.

## Technology Stack

- **Java 21** — required (uses virtual threads)
- **Javalin 7 / Jetty 12** — HTTP server
- **Jackson** — JSON processing (uses `JsonNode` for dynamic manipulation)
- **picocli** — CLI argument parsing
- **java.net.http** — outbound HTTP client (no extra dependency)
