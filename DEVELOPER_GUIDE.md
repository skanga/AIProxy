# AIProxy Developer Guide

This document explains the architecture, design decisions, and internals of the AIProxy project for developers who want to understand, modify, or extend the codebase.

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Build and Run](#build-and-run)
- [Project Structure](#project-structure)
- [Architecture](#architecture)
- [Package Walkthrough](#package-walkthrough)
  - [config](#config)
  - [auth](#auth)
  - [transport](#transport)
  - [sse](#sse)
  - [state](#state)
  - [model](#model)
  - [server](#server)
  - [Entry Point](#entry-point)
- [Request Flow](#request-flow)
- [Chat Completions Translation Layer](#chat-completions-translation-layer)
- [SSE Streaming](#sse-streaming)
- [Concurrency Model](#concurrency-model)
- [Design Decisions](#design-decisions)
- [Extending the Project](#extending-the-project)

---

## Overview

AIProxy is a local HTTP proxy that translates standard OpenAI API calls for three providers: Codex, Anthropic and GitHub Copilot. Codex uses ChatGPT credentials from `auth.json`; Claude uses a proxy-owned OAuth credential or `CLAUDE_CODE_OAUTH_TOKEN`.

The providers expose different wire protocols. Requests are normalized into a shared canonical model, routed by provider/model, and encoded back into OpenAI Chat Completions or Responses output without mixing provider-specific transport logic into the public API layer.

## Technology Stack

| Component | Library | Why |
|---|---|---|
| HTTP Server | Javalin 7.2.3 (Jetty 12) | Lightweight, virtual thread support, simple handler API |
| JSON | Jackson 3.2.2 (`ObjectMapper`, `JsonNode`) | Dynamic JSON manipulation without POJOs, industry standard |
| CLI | picocli 4.7.7 | Annotation-driven argument parsing, auto-generated help |
| HTTP Client | `java.net.http.HttpClient` | Built-in, supports streaming via `InputStream`, no extra deps |
| Logging | SLF4J 2.0.19 + slf4j-simple | Satisfies Javalin/Jetty's SLF4J requirement |
| Concurrency | Virtual threads (Java 21) | Lightweight, scales to many concurrent connections |

## Build and Run

```bash
# Compile
mvn clean compile

# Package fat JAR
mvn package -DskipTests

# Run
java -jar target/AIProxy-5.1.jar [serve] [options]

# Run tests
mvn test
```

The `maven-shade-plugin` produces a self-contained JAR with all dependencies at `target/AIProxy-5.1.jar`.

## Project Structure

```text
src/main/java/com/aiproxy/
  ProxyApplication.java        Application launcher
  cli/                        Command families, shared options and terminal rendering
  bootstrap/                  Provider construction, startup and lifecycle
  config/                     Configuration loading and immutable settings
  server/                     HTTP middleware, dispatch and backend contracts
  routing/                    Model routes and provider selection
  model/                      Shared model types and catalog contracts
  provider/
    spi/                      Shared requests, completion events and errors
    codex/                    Codex backends/client/policies; auth/ and model/
    anthropic/                Anthropic backends/client/policies; auth/ and model/
    copilot/                  Copilot backends/client/policies; auth/ and model/
  protocol/
    shared/                   Shared image parsing and stream event bookkeeping
    chat/                     Chat request codecs, stream decoding and completion encoding
    responses/                Responses request codecs, event codecs and stream collection
    messages/                 Reusable Messages codecs and beta-header parsing
  auth/                       Shared managed credential paths
  transport/                  Bounded reads, connection classification and URLs
  sse/                        Provider-independent SSE framing/parsing
  state/                      Response history and bounded namespace storage
  logging/, usage/, util/     Shared infrastructure
```

## Architecture

`ProxyApplication` invokes `cli.ProxyCommand`. Commands resolve configuration and delegate startup to `bootstrap.ProxyRuntime`. `ProviderAssembly` builds the provider backends and supplies `ProxyEndpoints` to `ProxyServer`.

CLI files group related commands: each provider's authentication family owns its nested login/logout commands and provider-specific options; `AuthCommand` owns status, `KeyCommand` owns generate, and `ConfigCommand` owns show. Shared serving options and substantial rendering helpers remain separate. These classes share package-private CLI context, so command families do not require separate subpackages or additional public APIs.

`protocol` is organized by wire format, not provider. `protocol.chat` owns `ChatRequestDecoder`, `ChatRequestEncoder`, `ChatStreamDecoder` and `ChatCompletionEncoder`; `protocol.responses` owns the corresponding Responses request/event codecs and stream collector. The concrete stream decoders compose the small `protocol.shared.CompletionStreamEvents` helper for event bookkeeping; they have no shared base class or protocol-selection flag. `protocol.messages` uses `Messages*` names for its request encoder, stream decoder, encoding exception, error parser, usage observer and beta-header parser.

Request decoders convert wire JSON to normalized requests, request encoders perform the reverse mapping, and stream decoders convert SSE to normalized completion events. `provider.spi.ChatRequestValidation` owns shared tool-declaration checks. Messages deliberately skips those checks when tools are disabled; OpenAI encoders still validate declarations. `protocol.shared.InlineImageDecoder` applies the same bounded base64 data-URL parsing to Chat and Responses inputs. Native Anthropic request validation uses `IllegalArgumentException` (mapped to HTTP 400), leaving `MessagesEncodingException` specific to encoding. Provider wrappers retain forced streaming, storage settings, usage requests and diagnostic wording. `AnthropicWire` also prepends the OAuth system identity; the shared Messages encoder respects the request's stream setting and injects no identity. Copilot reuses these OpenAI and Messages formats, so there is no separate Copilot wire protocol.

Chat Completions and Responses each register an `InferenceDispatchHandler` with an explicit `InferenceApi`. It selects an `InferenceBackend`, checks capabilities for opt-in failover and pins replay routes. Backends do not infer the requested API from a URL. Native Messages uses a separate `MessagesDispatchHandler` and `MessagesBackend` contract; it never participates in inference failover.

Codex owns `CodexChatBackend`, `CodexResponsesBackend`, `CodexHttpClient`, authentication and model discovery. Its existing Chat-to-Responses translation is retained. Anthropic and Copilot use shared normalized requests/events and protocol adapters where applicable. Copilot can use the Messages wire protocol without depending on the Anthropic provider implementation.

Keep authentication, endpoint discovery, compatibility headers and provider restrictions inside their provider package. Keep reusable wire formats under `protocol`. A provider must not import another provider implementation. Shared protocol/model/SSE/state/SPI code must not depend on provider implementations or HTTP server code. `PackageBoundariesTest` checks these boundaries.

`ReplayStateStore` centralizes bounded namespace storage. Each backend owns a separate instance and constructs its own client/account namespace. `ResponsesState` retains response/item expansion semantics. `BoundedBodyReader.readError` centralizes bounded reads while callers choose the overflow fallback and retain stream ownership.

Naming: `Handler` means HTTP endpoint/dispatch, `Backend` means provider execution, `HttpClient` means authenticated upstream transport, and `ModelCatalog` means provider discovery/cache. Request adapters read client formats; request encoders write upstream formats; stream decoders read upstream events; event encoders write client events. Prefer small contracts and composition over a common provider superclass. Tests mirror the production package for their subject.

## Package Walkthrough

### config

**`ServerConfig.java`** - A Java `record` holding immutable runtime configuration, including host/port, OAuth settings, API keys, CORS, request logging, prompt-cache forwarding, and Codex instruction options.

`ServerConfig` also carries compatibility and diagnostics flags: request logging, prompt-cache header forwarding, and optional Codex instruction source/cache settings. Full request logging defaults off because request bodies can contain prompts, tool outputs, paths, and other sensitive data.

### logging

`RequestLogger` is an opt-in JSON request logger. It logs inbound and upstream request/response metadata only when enabled, caps body capture, marks truncation, and redacts sensitive headers case-insensitively (`Authorization`, API keys, cookies, token/secret/key-like headers). Logging failures are warnings and must not fail proxied requests.

`ProxyServer` also prints a default one-line access log to the CLI for each request. That log is intentionally minimal: timestamp, method, path, status, duration, request ID, stream/sync mode when known, request content length, response status, and response byte count. Do not add headers, query strings, bodies, API keys, or OAuth material to the access log.

### auth

Shared `auth` contains managed credential paths. The Codex classes described below live in `provider.codex.auth`; `JwtParser` lives in `util`. Native Codex credentials and OAuth verification live in `provider.codex.auth.nativeoauth`. Copilot credentials/device OAuth live in `provider.copilot.auth`.

**`JwtParser.java`** — Decodes JWT tokens without verification (we only need the payload claims). Uses `Base64.getUrlDecoder()` to decode the middle segment, then parses with Jackson. The `deriveAccountId()` method extracts `chatgpt_account_id` from the `https://api.openai.com/auth` claim in the `id_token`.

**`CodexAuthFileResolver.java`** — Resolves candidate paths for `auth.json` in priority order: explicit path, `$CODEX_HOME`, then `~/.codex/`. Also determines the write-back path for refreshed tokens.

**`CodexAuthLoader.java`** — The core authentication logic:
- Reads `auth.json` from the first candidate path that exists
- Checks if the access token needs refreshing (expired within 5 minutes, or last refresh was >55 minutes ago)
- Refreshes via POST to the OAuth token endpoint with `grant_type=refresh_token`
- Writes updated tokens back to the auth file
- Returns an `AuthResult` record with `accessToken`, `accountId`, etc.

**`CodexAuthManager.java`** — Thread-safe wrapper around `CodexAuthLoader`. Caches the current `AuthResult` and provides `getAuthHeaders()` which returns a map with `Authorization`, `chatgpt-account-id`, and `OpenAI-Beta` headers. Uses `ReentrantLock` for safe concurrent access.

Claude authentication lives under `provider/anthropic/auth`. `AnthropicAuthCommands` owns interactive login/logout; `AnthropicCredentialStore` provides bounded parsing, atomic replacement, locking, and restrictive permissions; `AnthropicAuthManager` serializes refresh and retries one pre-body 401. An environment access token is deliberately non-refreshable. Never log access tokens, refresh tokens, authorization codes, PKCE verifiers, signed reasoning, or redacted thinking.

### transport

**`UrlResolver.java`** — Given an input path like `/v1/models` and a base URL like `https://chatgpt.com/backend-api/codex`, it:
1. Strips the base path prefix if present
2. Strips the `/v1` prefix
3. Reconstructs the full URL: `https://chatgpt.com/backend-api/codex/models`

**`provider.codex.CodexHttpClient.java`** — Wraps `java.net.http.HttpClient` with auth header injection. Provides two methods:
- `request()` — Returns `HttpResponse<InputStream>` for streaming
- `requestString()` — Returns `HttpResponse<String>` for simple responses

Both methods resolve URLs via `UrlResolver` and inject auth headers via `CodexAuthManager`.

### sse

**`ServerSentEvent.java`** — Simple `record(String event, String data)`.

**`SseParser.java`** — Parses Server-Sent Events from an `InputStream` using `BufferedReader`. Handles:
- `event:` lines → event type
- `data:` lines → event data (multiple data lines joined with `\n`)
- Blank lines → event boundaries
- Supports both batch parsing (`parse()`) and callback-based iteration (`iterateEvents()`), the latter used for streaming to avoid buffering the full stream.

**`protocol.responses.ResponsesStreamCollector.java`** — Iterates over an SSE stream looking for events whose JSON `data` contains a `response` object. Returns the last such object found. Used to extract the final completed response from an always-streaming upstream. Tracks `error` events for diagnostics.

### state

**`ResponsesState.java`** — Bounded LRU caches for the Responses API's stateful features:
- **Items cache** (max 2,000): Maps item IDs to their JSON objects, enabling `item_reference` expansion
- **Responses cache** (max 256): Maps response IDs to their input/output pairs, enabling `previous_response_id` expansion

Uses `LinkedHashMap` with `removeEldestEntry` for automatic LRU eviction. All public methods are `synchronized` for thread safety. Key operations:
- `requiresCachedState()` — Checks if a request body references cached state
- `expandRequestBody()` — Replaces `previous_response_id` and `item_reference` with actual cached data
- `rememberResponse()` — Caches a response's output items and input/output pair

> **Note:** `ResponsesState` is wired into `CodexResponsesBackend` as a best-effort, same-process replay cache. It is scoped per API key when key enforcement is enabled and is not durable storage.

### model

Shared `model` contains catalog contracts, model records and aggregation. Each provider owns its catalog/parser in `provider.<name>.model`. The following details describe Codex discovery.

**`CodexModelResolver.java`** — Discovers available models with multi-level caching:

1. **Codex version resolution** (cached 1 hour):
   - Explicit `--codex-version` flag
   - Local CLI: `ProcessBuilder("codex", "--version")`
   - NPM registry: `GET https://registry.npmjs.org/@openai/codex/latest`
   - Fallback: `0.111.0`

2. **Model list** (cached 5 minutes):
   - Fetches `GET /models?client_version=X` from upstream
   - Extracts `slug` from each model entry
   - Deduplicates

Both caches use double-checked locking with `ReentrantLock` and `volatile` fields.

**`CodexModelAliasResolver.java`** - Normalizes convenience aliases such as `gpt-5.2-codex-xhigh` to the backend model plus a default `reasoning.effort`. It also clamps unsupported reasoning values before forwarding, which avoids preventable upstream 400s.

**`CodexInstructionsProvider.java`** - Supplies configured instructions by default. In opt-in `latest-codex` mode, it fetches model-family instructions, caches them for 15 minutes, sends conditional requests when an ETag exists, and falls back to stale cache or configured instructions on fetch failure.

### server

**`JsonHelper.java`** — Utility class providing:
- `toJsonResponse()` / `toErrorResponse()` — Standard JSON response formatting (OpenAI error format)
- `mapFinishReason()` — Translates between upstream and OpenAI finish reasons
- `toUsage()` — Converts upstream usage JSON to OpenAI format (prompt/completion/total tokens, with optional detail breakdowns)
- `setCorsHeaders()` / `setSseHeaders()` — Standard header configuration

**`HealthHandler.java`** — Returns safe liveness/status fields: `ok`, `service`, `version`, and `uptime_seconds`.

**`ModelsHandler.java`** — Lists the shared catalog with provider ownership/capability metadata and delegates native Anthropic model requests to the supplied backend.

**`provider.codex.CodexResponsesBackend.java`** — Passthrough to upstream `/responses` with normalization:
1. Validates body is a JSON object
2. Expands `previous_response_id` and `item_reference` only from bounded in-memory same-process cache when available
3. Normalizes: forces upstream `stream=true`, sets model aliases, default `instructions`, and `store`
4. In stateless mode, strips item IDs, removes unresolved `item_reference`, and converts orphaned tool outputs to assistant messages
5. Optionally forwards `prompt_cache_key` as upstream conversation/session headers
6. Forwards to upstream
7. Maps usage-limit style upstream 404s to 429
8. If client wants streaming: pipes SSE directly
9. If non-streaming: collects completed response via `ResponsesStreamCollector`, records usage, and remembers it only in memory

**`provider.codex.CodexChatBackend.java`** — The most complex handler. See [Chat Completions Translation Layer](#chat-completions-translation-layer).

**`ProxyServer.java`** — Javalin application setup:
- Enables virtual threads (`useVirtualThreads = true`)
- Registers CORS preflight handler for `OPTIONS /*`
- Registers all route handlers
- Global exception handler returning OpenAI error format
- Custom 404 handler
- **API key enforcement** (opt-in): if `config.apiKeys()` is non-empty, a `beforeMatched` hook is registered after `Javalin.create()`. The hook skips `/health`, then checks the `Authorization: Bearer <key>` header against the key set. Invalid or missing keys get a `401` `auth_error` response and `ctx.skipRemainingHandlers()` short-circuits the request.

### Entry Point

`ProxyApplication.main()` runs the picocli `ProxyCommand`. Each command/options group has its own file under `cli`; `ConfigRenderer` and `StartupRenderer` own presentation. `ProxyRuntime` owns startup checks, resource lifecycle and provider construction through `ProviderAssembly`.

The shaded JAR manifest names `com.aiproxy.ProxyApplication` in the project/artifact jar `AIProxy-5.1.jar`.

## Request Flow

The following diagrams describe the Codex route after provider selection. Anthropic and Copilot select their own upstream protocols.

### Chat Completions (non-streaming)

```
Client                    AIProxy                        Upstream
  │                            │                                  │
  │  POST /v1/chat/completions │                                  │
  │  {"messages":[...]}        │                                  │
  │───────────────────────────>│                                  │
  │                            │  Translate messages → input      │
  │                            │  POST /responses {"stream":true} │
  │                            │─────────────────────────────────>│
  │                            │                                  │
  │                            │  SSE: response.output_text.delta │
  │                            │  SSE: response.completed         │
  │                            │<─────────────────────────────────│
  │                            │                                  │
  │                            │  Collect final response          │
  │                            │  Translate → chat.completion     │
  │  {"choices":[...]}         │                                  │
  │<───────────────────────────│                                  │
```

### Chat Completions (streaming)

```
Client                    AIProxy                         Upstream
  │                             │                                  │
  │  POST /v1/chat/completions  │                                  │
  │  {"stream":true}            │                                  │
  │────────────────────────────>│                                  │
  │                             │  POST /responses {"stream":true} │
  │                             │─────────────────────────────────>│
  │                             │                                  │
  │  SSE: chat.completion.chunk │ SSE: response.output_text.delta  │
  │  (role:assistant)           │<─────────────────────────────────│
  │<────────────────────────────│                                  │
  │                             │                                  │
  │  SSE: chat.completion.chunk │ SSE: response.output_text.delta  │
  │  (content delta)            │<─────────────────────────────────│
  │<────────────────────────────│                                  │
  │                             │                                  │
  │  SSE: chat.completion.chunk │ SSE: response.completed          │
  │  (finish_reason:stop)       │<─────────────────────────────────│
  │<────────────────────────────│                                  │
  │  SSE: [DONE]                │                                  │
  │<────────────────────────────│                                  │
```

## Chat Completions Translation Layer

This is the core logic in `CodexChatBackend`. It bridges two different API formats.

### Request Translation (Chat → Responses API)

| Chat Completions Field | Responses API Field |
|---|---|
| `messages` with `role: "system"` or `"developer"` | `instructions` parameter (concatenated) |
| `messages` with `role: "user"` | `input` item: `{type:"message", role:"user", content:[{type:"input_text", text:"..."}]}` |
| `messages` with `role: "assistant"` (text only) | `input` item: `{type:"message", role:"assistant", content:[{type:"output_text", text:"..."}]}` |
| `messages` with `role: "assistant"` + `tool_calls` | `input` items: text message + `{type:"function_call", call_id, name, arguments}` per tool call |
| `messages` with `role: "tool"` | `input` item: `{type:"function_call_output", call_id, output:"..."}` |
| `model` | `model` |
| `temperature`, `top_p` | `temperature`, `top_p` |
| `max_tokens` | `max_output_tokens` |
| `tools` (function definitions) | `tools` (same structure, wrapped in `{type:"function", ...}`) |
| `tool_choice` | `tool_choice` (passed through) |
| `reasoning_effort` | `reasoning: {effort: "..."}` |

### Upstream SSE Event → Chat Completion Chunk Translation

| Upstream SSE Event | Chat Completion Chunk |
|---|---|
| (initial) | `delta: {role: "assistant"}`, `finish_reason: null` |
| `response.output_text.delta` | `delta: {content: "..."}`, `finish_reason: null` |
| `response.output_item.added` (function_call) | `delta: {tool_calls: [{index, id, type:"function", function:{name, arguments:""}}]}` |
| `response.function_call_arguments.delta` | `delta: {tool_calls: [{index, function:{arguments:"..."}}]}` |
| `response.completed` | `delta: {}`, `finish_reason: "stop"/"tool_calls"/"length"` + usage chunk |

### Non-Streaming Response Translation

For non-streaming requests, the handler collects the completed response from the SSE stream and builds a `chat.completion` object:

- Iterates `response.output[]` items
- `type: "message"` → extracts `output_text` parts into `message.content`
- `type: "function_call"` → builds `message.tool_calls[]`
- `response.status` → mapped to `finish_reason` (`completed`→`stop`, `incomplete`→`length`)
- `response.usage` → mapped to `usage` object

## SSE Streaming

### Upstream → Proxy (parsing)

`SseParser.iterateEvents()` reads from `InputStream` line by line:
- Blank lines delimit event boundaries
- `event:` prefix → event type
- `data:` prefix → event data (multiple `data:` lines joined with `\n`)
- Accepts a `Consumer<ServerSentEvent>` callback, enabling real-time processing without buffering

### Proxy → Client (writing)

For streaming responses, the handler writes directly to `ctx.res().getOutputStream()`:
- Each chunk is formatted as `data: {json}\n\n`
- The stream is flushed after every chunk for low latency
- SSE headers are set: `Content-Type: text/event-stream`, `Cache-Control: no-cache, no-transform`, `Connection: keep-alive`, `X-Accel-Buffering: no`

## Concurrency Model

- **Virtual threads**: Javalin is configured with `useVirtualThreads = true`. Each incoming request runs on a virtual thread, allowing thousands of concurrent connections without thread pool exhaustion.
- **Auth refresh**: `CodexAuthManager` uses `ReentrantLock` to prevent concurrent token refreshes. Only one thread performs the refresh; others wait.
- **Model cache**: `CodexModelResolver` uses double-checked locking with `volatile` fields and `ReentrantLock`.
- **State cache**: `ResponsesState` uses `synchronized` methods on all public operations.
- **HTTP client**: `CodexHttpClient` creates its internal `HttpClient` with a virtual thread executor.

## Design Decisions

### Why Jackson `JsonNode` instead of POJOs?

The upstream API returns dynamic, deeply nested JSON structures that vary by event type. Using `JsonNode` for dynamic manipulation avoids creating dozens of data classes and provides the flexibility needed for passthrough and transformation. Only `ServerConfig` uses a Java `record` since its shape is fixed and known at compile time.

### Why Javalin over Spring Boot / Micronaut / etc.?

Javalin is a micro-framework with minimal ceremony — no annotation scanning, no dependency injection container, no auto-configuration. It starts in milliseconds, has built-in virtual thread support, and is a natural fit for a lightweight proxy server.

### Why `maven-shade-plugin` for the fat JAR?

Shade produces a single self-contained JAR that bundles all dependencies. Users run it with a simple `java -jar` command with no classpath setup. The `ManifestResourceTransformer` sets the main class, and signature file exclusion prevents JAR signing conflicts.

### Why `InputStream`-based streaming instead of reactive streams?

Virtual threads make blocking I/O efficient. Reading from `InputStream` with `BufferedReader` is straightforward, debuggable, and doesn't require reactive programming patterns. The upstream SSE events are parsed line by line and forwarded to the client incrementally.

## Extending the Project

### Adding a new endpoint

1. Create a new `Handler` class in `com.aiproxy.server`
2. Register it in `ProxyServer.java`'s constructor

### Responses replay state

The project intentionally avoids durable local Responses storage. `ResponsesState` is a bounded in-memory compatibility cache for same-process replay only. Non-streaming Responses populate the cache after `ResponsesStreamCollector` sees the completed response. Streaming Responses are forwarded to clients while the proxy performs bounded best-effort SSE bookkeeping for usage and replay cache population after a `response.completed` event. Do not add database-backed response/conversation emulation unless the project explicitly introduces an opt-in stateful mode with tenant scoping and privacy documentation.

### Adding request logging

Use the existing `RequestLogger` and keep logging opt-in. New log fields must pass through the same redaction path, and streaming response bodies must not be buffered solely for logs.

### Changing the upstream API

Modify `UrlResolver.resolveTargetUrl()` for path mapping changes, or override `--codex-base-url` at runtime. Each provider owns its HTTP client; adjust the appropriate provider client for authentication or upstream URL changes. Shared URL helpers should contain only reusable normalization.
