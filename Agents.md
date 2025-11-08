# Agents.md - JetBrains Codex Plugin

> Goal: build a JetBrains IDE plugin that talks to the Codex CLI “app server” to run conversations, surface tool calls, and live-stream events in a first-class IDE UI.

---

## 1) Scope

**In scope**

- Launch and manage `codex app-server` as a subprocess
- Speak its JSON-RPC over STDIN/STDOUT (JSON Lines), implement the init handshake, and multiplex conversations
- Live event stream UI: reasoning, tool calls, exec output, plan updates, usage, errors
- Chat UI: send user turns with text and attachments
- Account and rate limit surfaces
- Project-aware defaults: model, cwd, sandbox policy, approval policy

**Out of scope for v1**

- Editing remote files outside the IDE workspace
- Non-Codex providers
- Offline replay editor beyond basic rollout open/view

---

## 2) Ground truth for the wire protocol

We integrate with the Codex CLI in “app server” mode. Transport is JSON Lines over STDIN/STDOUT with JSON-RPC envelopes, but without the `"jsonrpc": "2.0"` field. Requests use `{ "id", "method", "params" }` and responses use `{ "id", "result" | "error" }`. Notifications omit `id`.

Required handshake: call `initialize`, receive its `result`, then send an `initialized` notification before any other method. Until that notification, the server returns `Not initialized`.

Core methods we rely on (per `app-server-doc.md`):

- Threads: `thread/start`, `thread/resume`, `thread/list`, `thread/archive`
- Turns: `turn/start`, `turn/interrupt`
- Items & history: turn streams emit `thread/started`, `turn/started`, `item/*`, and `turn/completed` notifications automatically; no explicit listener registration is required after a thread starts.
- Accounts and models: `account/read`, `account/login/start`, `account/login/cancel`, `account/logout`, `account/rateLimits/read`, `model/list` (plus `account/login/completed` and `account/updated` notifications)
- Misc tooling: `execOneOffCommand`, `fuzzyFileSearch`, `gitDiffToRemote` as needed

Server-initiated approvals: `execCommandApproval` and `applyPatchApproval` arrive as JSON-RPC requests that require a `{ "decision": "approved" | "approved_for_session" | "denied" | "abort" }` result. Default to denied on parse failures.

Thread lifecycles stream raw JSON objects that include `threadId`, `turnId`, and concrete `item` payloads (text, image, localImage). We normalize these notifications into UI-friendly events while preserving enough metadata for replay and telemetry.

---

## 3) Architecture

### Modules

1. **ProcessManager**
   - Spawns `codex app-server` with project-scoped env and working directory
   - Restarts with backoff on crash
   - Stderr is piped to IDE logs

2. **JsonRpcClient**
   - Frames JSON Lines, assigns request ids, correlates results
   - Notification bus with backpressure
   - Strict decoding with unknown-field tolerance

3. **SessionRegistry**
   - Tracks `threadId`, current `turnId`, and lifecycle state
   - Handles `turn/interrupt`, shutdown, and replay open

4. **EventRouter**
   - Parses `thread/*`, `turn/*`, and `item/*` notifications into typed Kotlin events
   - Aggregates streaming item deltas (text vs reasoning) into stable UI items
   - Emits rate-limit snapshots on `account/rateLimits/updated`

5. **ApprovalService**
   - Surfaces modal sheets for `execCommandApproval` and `applyPatchApproval`
   - Persists per-session “approve similar” decisions

6. **ChatToolWindow**
   - Left: thread transcript with reasoning and item blocks
   - Right: Tooling timeline (tool calls, exec, patches) with filters
   - Composer: multi-line input, file picker, effort knob, policy toggles

7. **Settings & Model Picker**
   - Codex binary path, CLI flags, default model, profile, sandbox and approval policies, telemetry toggle

### Threading & performance

- Use Kotlin coroutines with a single writer to STDIN and a reader actor for STDOUT
- UI updates through a conflated flow to avoid flood during deltas
- Parse on background dispatcher, marshal to EDT for UI

---

## 4) Key flows

### Startup

1. Spawn `codex app-server`
2. Send `initialize { clientInfo: {name,title,version} }`, await `result`
3. Send `initialized` notification
4. Optionally call `account/read` and `account/rateLimits/read`

### Start a thread

1. `thread/start { model, cwd, approvalPolicy, sandbox, baseInstructions?, developerInstructions? }` → keep `threadId`  
2. Server immediately emits `thread/started { thread }`; once received we mark the composer as ready.

### Send a turn

- `turn/start { threadId, input: [{type:"text", "text":"..."}, ...], effort?, summary?, sandboxPolicy?, model? }`  
- Capture `turn.id` from the response; stream `turn/started`, `item/*`, and `turn/completed` notifications until status is `completed` or `interrupted`. Surface token usage via `turn.completion` metadata or follow-up `account/rateLimits/updated` signals.

### Approvals

- On `execCommandApproval` or `applyPatchApproval`, show a diff or command preview with risk hints
- Post a decision result to the same `id` within a timeout to avoid server defaults

### Abort

- `turn/interrupt { threadId, turnId }` then watch for the matching `turn/completed` with `status:"interrupted"` before re-enabling the composer.

---

## 5) Event mapping to UI

We render these as timeline items and badges:

- Lifecycle: `thread/started` (thread metadata), `turn/started`, `turn/completed` (with status `completed` | `interrupted`)
- Items: `item/created` (full payload) and `item/delta` (streaming text). We distinguish reasoning vs response by inspecting the item `role`/`purpose` fields described in `app-server-doc.md`.
- Plan: `item/created` with `type:"plan"` becomes the todo list; `item/delta` updates a plan entry.
- Tool calls: `item/created` where `type:"tool_call"` ± follow-up exec notifications detail arguments/result state.
- Exec/Search/Patch: still powered by `exec_command_*`, `web_search_*`, `apply_patch_*` notifications emitted as part of the turn timeline.
- Usage: `turn/completed` includes `tokenUsage`, and we also watch `account/rateLimits/updated` to keep counters fresh in the header.
- Errors & warnings: `turn/completed` with `status:"failed"`, `error`, `warning`, `deprecation_notice` events land in a Problems stripe.

---

## 6) Data contracts we rely on

- `InputItem` objects for `turn/start` are discriminated unions: `{"type":"text","text":"..."}`, `{"type":"image","url":"https://..."}`, `{"type":"localImage","path":"/tmp/screenshot.png"}`. Attachments in the UI map directly onto this list.
- Reasoning knobs per turn: `effort: minimal|low|medium|high`, `summary: auto|concise|detailed|none`
- Approval policy: `unlessTrusted | onFailure | onRequest | never`
- Sandbox policy: `read-only | danger-full-access | workspace-write` with optional writable roots and network flag  
All shapes and enums above come from the app server protocol.

---

## 7) UX details

- **Chat panel**
  - Markdown rendering with code fences and copy buttons
  - Attachments: images become `localImage` items
  - Effort selector and summary mode in a compact toolbar

- **Timeline panel**
  - Dense list of tool call cards with expandable details
  - Filters for categories: Tools, Exec, Patches, Search, Plan, Errors

- **Approvals**
  - Command preview with cwd, parsed command, risk hint
  - Patch preview with unified diffs and write scope
  - Buttons: Approve, Approve for session, Deny, Abort

- **Usage header**
  - Tokens this turn and total, plus current rate-limit window if provided by backend

- **Replay**
  - Open a rollout by id or path, show `thread/resume` metadata, allow `thread/archive`

---

## 8) Safety, privacy, and defaults

- Default approval decision is Deny if anything is malformed or times out. The protocol treats unparseable replies as denial.
- Sandbox policy defaults to workspace-write with no network
- Do not exfiltrate project files in telemetry
- Mask secrets in logs and approval dialogs


### Web search requirement

- The coding agent must use web search for the latest information. Default to searching when topics may have changed recently or are time sensitive. Provide citations in the timeline unless the user opts out.

---

## 9) Failure handling

- If any call occurs before `initialized`, surface a banner and re-run the handshake automatically.
- On `-32603` internal errors, log, show toast, and allow retry of the last action.
- Unknown `thread/*`, `turn/*`, or `item/*` notifications are logged and ignored to stay forward compatible.

---

## 10) Build, run, and test

- Plugin: Kotlin + Gradle IntelliJ Plugin, UI in Swing with JCEF renderers where useful
- Local run: detect Codex binary from settings or `$PATH`, verify by calling `model/list`
- Integration tests:
- Start a real `codex app-server` in CI, handshake, create a thread, run a trivial turn, assert event ordering through `turn/completed`
  - Simulate approvals by auto-approving a known `git status` to validate request-response routing
- Fuzz tests: feed truncated or merged JSON lines to ensure our decoder recovers without freezing the UI

---

## 11) Configuration surface

- **Codex**
  - Binary path
  - Extra CLI flags (`-c key=value`)
  - Default model and profile
  - Approval and sandbox policies
- **Threads & Turns**
  - Effort default
  - Summary mode default
- **Telemetry**
  - Toggle and log level

---

## 12) Definition of Done for v1

- Handshake, thread start, turn start, streaming render, and graceful completion
- Approvals round trip for exec and patch apply
- Timeline shows tool calls, exec, plan updates, search, patches, errors
- Usage and rate limit header updates live
- Robust recovery from server restarts and malformed lines
- Unit and integration tests pass on CI

---

## 13) Quick wire examples

**Initialize then signal readiness**

```json
{"id":1,"method":"initialize","params":{"clientInfo":{"name":"jetbrains-codex","title":"JetBrains Codex Plugin","version":"0.1.0"}}}
{"id":1,"result":{"userAgent":"..."}}
{"method":"initialized"}
```

**Start a thread**

```json
{"id":2,"method":"thread/start","params":{"model":"gpt-5-codex","cwd":"/project","approvalPolicy":"onRequest","sandbox":"workspace-write"}}
{"method":"thread/started","params":{"thread":{"id":"thr_123","modelProvider":"openai","createdAt":1730910000}}}
```

**Start a turn**

```json
{"id":3,"method":"turn/start","params":{"threadId":"thr_123","input":[{"type":"text","text":"Hello Codex!"}],"effort":"medium","summary":"auto"}}
{"method":"turn/started","params":{"threadId":"thr_123","turn":{"id":"turn_456","status":"inProgress"}}}
{"method":"item/created","params":{"threadId":"thr_123","turnId":"turn_456","item":{"type":"assistant","textDelta":"Hi there!"}}}
{"method":"turn/completed","params":{"threadId":"thr_123","turnId":"turn_456","status":"completed","tokenUsage":{"input":123,"output":456}}}
```

Events continue with additional `item/delta`, `exec_command_*`, `web_search_*`, and `account/rateLimits/updated` notifications depending on the turn.

---

## 14) Work plan and agents

- **ProtocolAdapter** - implements JsonRpcClient, envelopes, id correlation, and reconnection
- **EventIngestor** - decodes `thread/*`, `turn/*`, and `item/*` notifications, builds stable item model, aggregates deltas
- **ChatOrchestrator** - manages threads, composer state, effort and summary knobs
- **ApprovalsAgent** - UX for exec and patch approvals, session-scoped rules
- **UsageAgent** - tracks `token_count` and rate limits, updates header
- **SettingsAgent** - validates Codex path and runs `model/list` on save
- **QA Agent** - builds replay suite and CI integration against real app server

Each agent ships with unit tests and logs only to the plugin tool window unless debug mode is enabled.

---

## 15) Notes for future versions

- Multi-provider abstraction that preserves Codex features
- Rich diff review with inline accept-reject per hunk
- Workspace search UI driven by `fuzzyFileSearch`
- One-off `execOneOffCommand` terminal widget for quick checks

## Important

When writing complex features or significant refactors, use an ExecPlan (as described in .agent/PLANS.md) from design to implementation.

**References**

Use `app-server.md`

Protocol details, methods, event catalog, approvals, and error semantics are derived from the Codex “app server” JSON-RPC specification and lifecycle guide.
