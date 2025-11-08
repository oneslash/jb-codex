# Codex App Server JSON-RPC Protocol

This document reverse engineers the JSON-RPC surface that the Codex CLI exposes when run in “app server” mode (`codex app-server`). It consolidates the Rust protocol definitions (`codex-rs/app-server-protocol`) and the message handling logic in the app-server runtime (`codex-rs/app-server`) so that external clients—such as a JetBrains IDE plugin—can interoperate without depending on the official VS Code extension or the OpenAI-hosted APIs.

> **Scope.** All examples target the CLI build dated 2025-11-05. The protocol is *not* guaranteed to be stable across releases; regenerate schemas (`codex generate-ts`) whenever you upgrade Codex.

---

## 1. Transport and Framing

- **Process:** spawn `codex app-server` and communicate over its STDIN/STDOUT.
- **Encoding:** UTF‑8 JSON Lines (one JSON document per line).
- **Protocol:** JSON-RPC 2.0 *without* the `"jsonrpc": "2.0"` field. Messages are distinguished by shape:
  - Requests: `{"id": <RequestId>, "method": "...", "params": {...}}`
  - Responses: `{"id": <RequestId>, "result": {...}}`
  - Notifications: `{"method": "...", "params": {...}}`
  - Errors: `{"id": <RequestId>, "error": {"code": <i64>, "message": "...", "data": ...}}`
- **Request IDs:** `string` or `integer`. The server always emits integers; clients may use either.

## 2. Bootstrapping the Server

1. Launch `codex app-server` with any desired CLI overrides (for example, `codex app-server -c key=value`).
2. Read STDOUT continuously; each line is a complete message. Write JSON lines to STDIN.
3. Observe stderr for diagnostics only. It is not part of the protocol.

### Authentication notes

The app server uses the same credential store as the CLI. Most integrations will authenticate once via the `account/*` or legacy `login*` methods and persist tokens under `~/.codex`.

## 3. Initialization Handshake

Every client must complete the handshake before issuing other requests.

1. **Request:** `initialize`

   ```json
   {
     "id": 1,
     "method": "initialize",
     "params": {
       "clientInfo": {
         "name": "jetbrains-codex",
         "title": "JetBrains Codex Plugin",
         "version": "0.1.0"
       }
     }
   }
   ```

2. **Response:** `{"id":1,"result":{"userAgent":"codex/<version>; <client>"}}`

   The server records `name/version` to augment the outbound `User-Agent`.

3. **Client acknowledgement:** send a notification declaring readiness:

   ```json
   {"method":"initialized"}
   ```

Until the notification arrives, every other request is rejected with `{"code": -32600, "message": "Not initialized"}`.

## 4. Client → Server Methods

The protocol exposes two tiers of methods:

| Tier | Method string | Params type | Result type | Notes |
| --- | --- | --- | --- | --- |
| **v2 (current)** | `model/list` | `ListModelsParams` | `ListModelsResponse` | Enumerate Codex model presets (id, display name, supported reasoning efforts, default). |
| | `account/login` | `LoginAccountParams` | `LoginAccountResponse` | Starts login. ChatGPT flows return `loginId` + `authUrl`; API-key flows succeed immediately. |
| | `account/logout` | `undefined` | `LogoutAccountResponse` | Revokes current account session. |
| | `account/read` | `undefined` | `GetAccountResponse` | Returns the active account (API key or ChatGPT). |
| | `account/rateLimits/read` | `undefined` | `GetAccountRateLimitsResponse` | On success also triggers `account/rateLimits/updated` notifications when usage changes. |
| | `feedback/upload` | `UploadFeedbackParams` | `UploadFeedbackResponse` | Files user feedback including optional logs. |

Legacy methods (still required for conversations) use camelCase identifiers:

| Method | Params | Result | Notes |
| --- | --- | --- | --- |
| `newConversation` | `NewConversationParams` | `NewConversationResponse` | Creates a Codex session. Specify `model`, `profile`, `cwd`, sandbox/approval overrides, etc. |
| `resumeConversation` | `ResumeConversationParams` | `ResumeConversationResponse` | Replays a rollout from disk or a known conversation id. |
| `listConversations` | `ListConversationsParams` | `ListConversationsResponse` | Paginates recorded rollouts. |
| `getConversationSummary` | `GetConversationSummaryParams` | `GetConversationSummaryResponse` | Accepts either rollout path or conversation id. |
| `archiveConversation` | `ArchiveConversationParams` | `ArchiveConversationResponse` | Marks rollout as archived. |
| `sendUserMessage` | `SendUserMessageParams` | `SendUserMessageResponse` (empty) | Queues user input for current session. Drives the event stream. |
| `sendUserTurn` | `SendUserTurnParams` | `SendUserTurnResponse` (empty) | Advanced per-turn variant (explicit sandbox + approval policy). |
| `interruptConversation` | `InterruptConversationParams` | `InterruptConversationResponse` | Sends a cancel signal; server replies once the turn aborts. |
| `addConversationListener` | `AddConversationListenerParams` | `AddConversationSubscriptionResponse` | Starts streaming conversation events as notifications. |
| `removeConversationListener` | `RemoveConversationListenerParams` | `RemoveConversationSubscriptionResponse` | Stops a listener created earlier. |
| `sendUserMessage` | `SendUserMessageParams` | `{}` | Accepts `items: InputItem[]`. |
| `execOneOffCommand` | `ExecOneOffCommandParams` | `ExecOneOffCommandResponse` | Runs a sandboxed shell command on demand. |
| `loginApiKey`, `loginChatGpt`, `cancelLoginChatGpt`, `logoutChatGpt`, `getAuthStatus` | *various* | *various* | Deprecated in favor of the account/* endpoints. |
| `getUserAgent` | – | `GetUserAgentResponse` | Returns the full UA string. |
| `userInfo` | – | `UserInfoResponse` | Legacy account info. |
| `setDefaultModel` | `SetDefaultModelParams` | `SetDefaultModelResponse` | Persists default model choice. |
| `getUserSavedConfig` | – | `GetUserSavedConfigResponse` | Reads stored overrides. |
| `gitDiffToRemote` | `GitDiffToRemoteParams` | `GitDiffToRemoteResponse` | Uses Codex’s git helper to diff against remote. |
| `fuzzyFileSearch` | `FuzzyFileSearchParams` | `FuzzyFileSearchResponse` | Built-in fuzzy matcher used by editors. |

### Parameter shapes

- **`InputItem` (for `sendUserMessage` / `sendUserTurn`):**

  ```jsonc
  { "type": "text", "data": { "text": "..." } }
  { "type": "image", "data": { "imageUrl": "https://..." } }
  { "type": "localImage", "data": { "path": "/abs/path.png" } }
  ```

- **Approval policy (`AskForApproval`):** `"unlessTrusted" | "onFailure" | "onRequest" | "never"`.
- **Sandbox policy (`SandboxPolicy`):**

  ```jsonc
  { "mode": "read-only" }
  { "mode": "danger-full-access" }
  {
    "mode": "workspace-write",
    "writableRoots": ["/project/tmp"],
    "networkAccess": false,
    "excludeTmpdirEnvVar": false,
    "excludeSlashTmp": false
  }
  ```

- **Reasoning knobs (optional per turn):**
  - `effort`: `"minimal" | "low" | "medium" | "high"`
  - `summary`: `"auto" | "concise" | "detailed" | "none"`

## 5. Conversation Lifecycle Playbook

Typical flow for a single turn:

1. `initialize` / `initialized`.
2. `newConversation` → response includes `conversationId` and `model`.
3. `addConversationListener` with the `conversationId`.
   - `experimentalRawEvents: true` enables fine-grained deltas (`raw_response_item`).
4. Wait for `codex/event/session_configured` notification. Its payload advertises the resolved model, reasoning effort, and rollout path.
5. Send user input:

   ```json
   {
     "id": 4,
     "method": "sendUserMessage",
     "params": {
       "conversationId": "<uuid>",
       "items": [
         { "type": "text", "data": { "text": "Please make a hello world project." } }
       ]
     }
   }
   ```

6. Stream notifications (Section 6) until a `codex/event/task_complete` arrives.
7. Respond to any server-initiated `ApplyPatchApproval` / `ExecCommandApproval` requests (Section 7).
8. When finished, optionally call `removeConversationListener`.

To abort a long-running turn, issue `interruptConversation` with the same `conversationId`. The server completes the outstanding request with `{"abortReason":"interrupted"}` after it emits `codex/event/turn_aborted`.

## 6. Event Notifications

`addConversationListener` spawns a background task that converts every Codex `Event` into a JSON-RPC notification:

```json
{
  "method": "codex/event/task_started",
  "params": {
    "id": "evt-123",
    "msg": {
      "type": "task_started",
      "modelContextWindow": 32000
    },
    "conversationId": "<uuid>"
  }
}
```

Key properties:

- `method` is `codex/event/<snake_case EventMsg variant>`.
- `params.id` tracks the originating event id inside Codex; it is unique per event but not stable across runs.
- `params.msg` matches `codex_protocol::protocol::EventMsg`. The union is serialized with a `"type"` discriminator plus the variant’s payload fields.
- The server injects `params.conversationId` so clients can multiplex multiple sessions.
- If `experimental_raw_events` is `false`, `raw_response_item` events are suppressed.
- Turn-level usage snapshots (`token_count`) raise an additional server notification (`account/rateLimits/updated`) when rate-limit data changes.

### Event catalog

| Category | Event type (`params.msg.type`) | Payload highlights |
| --- | --- | --- |
| Turn lifecycle | `session_configured` | `sessionId`, `model`, optional `reasoningEffort`, `rolloutPath`, `initialMessages`. |
|  | `task_started` | `modelContextWindow`. One per user turn. |
|  | `task_complete` | `lastAgentMessage` (string or `null`). Signals end-of-turn. |
|  | `turn_aborted` | `{ "reason": "interrupted" \| "replaced" \| "review_ended" }`. |
| Usage | `token_count` | `info.totalTokenUsage`/`lastTokenUsage` plus optional `rateLimits`. |
| Agent output | `agent_message` | Text message in `message`. |
|  | `agent_message_delta` / `agent_message_content_delta` | Partial text; assemble for streaming UIs. |
|  | `agent_reasoning`, `agent_reasoning_delta`, `agent_reasoning_raw_content`, `agent_reasoning_raw_content_delta`, `agent_reasoning_section_break` | Higher- and lower-level reasoning traces. |
| Plan/TODO | `plan_update` | `explanation?: string`, `plan: [{step,status}]`; SDK maps these to todo-list items. |
| Tool calls | `mcp_tool_call_begin` / `mcp_tool_call_end` | `callId`, `tool`, `server`, `arguments`, `result`, `error`. |
| Web search | `web_search_begin` / `web_search_end` | `callId`, `query`. End event is emitted when results are returned. |
| Shell exec | `exec_command_begin` | `callId`, `command[]`, `cwd`, `parsedCmd[]`, `isUserShellCommand`. |
|  | `exec_command_output_delta` | `callId`, `stream` (`stdout`/`stderr`), `chunk` (base64 bytes). |
|  | `exec_command_end` | Exit code, captured `stdout`/`stderr`, aggregated output and duration. |
| File patches | `apply_patch_approval_request` | `callId`, `fileChanges`, `reason`, optional `grantRoot`. Paired with a server request. |
|  | `patch_apply_begin` | `callId`, `autoApproved`, `changes`. |
|  | `patch_apply_end` | Success flag + aggregated logs. |
| Items | `item_started`, `item_completed`, `item_updated` | Wraps a `TurnItem` (agent messages, reasoning summaries, command executions, MCP tool calls, todo lists, web searches, errors). Item ids are stable within the turn. |
| Errors & warnings | `error`, `warning`, `stream_error`, `background_event`, `deprecation_notice` | Display to the user; stream errors mean Codex is retrying upstream. |
| Review mode | `entered_review_mode`, `exited_review_mode` | Signals the human-in-the-loop workflow. |
| Shutdown | `shutdown_complete` | Emitted when the conversation fully stops (e.g., after `removeConversationListener`). |

## 7. Server → Client Requests (Approvals)

The app server may interrupt the notification stream with JSON-RPC requests that **expect a response**. These correspond to `ServerRequest` variants.

### `execCommandApproval`

```json
{
  "id": 42,
  "method": "execCommandApproval",
  "params": {
    "conversationId": "<uuid>",
    "callId": "call-1",
    "command": ["git", "status"],
    "cwd": "/workspace/project",
    "reason": "Needs to inspect git state.",
    "risk": {
      "risk": "high",
      "comments": "Writes to repo"
    },
    "parsedCmd": [
      { "unknown": { "cmd": "git status" } }
    ]
  }
}
```

**Reply with:**

```json
{
  "id": 42,
  "result": { "decision": "approved" }
}
```

`decision` is a `ReviewDecision`: `"approved"`, `"approved_for_session"`, `"denied"` (default), or `"abort"`. If you cannot parse the payload, reply with `"denied"` to keep the agent safe.

### `applyPatchApproval`

```json
{
  "id": 43,
  "method": "applyPatchApproval",
  "params": {
    "conversationId": "<uuid>",
    "callId": "patch-8",
    "fileChanges": {
      "/app/new_file.rs": { "kind": "add", "content": "..." },
      "/app/lib.rs": { "kind": "update", "unifiedDiff": "@@ ..." }
    },
    "reason": "Need to create scaffolding.",
    "grantRoot": "/app"
  }
}
```

Reply with `{"decision":"approved"}` or similar. If you refuse, send `"denied"`—the agent will continue without applying changes.

The server correlates your responses back into the live conversation by submitting Codex operations internally.

## 8. Passive Server Notifications

Besides `codex/event/*`, the server emits higher-level notifications via `ServerNotification`:

| Method | Payload | Description |
| --- | --- | --- |
| `account/rateLimits/updated` | `RateLimitSnapshot` | Mirrors usage counters from the model backends. |
| `authStatusChange` (legacy) | `{ authMode: "apiKey" \| "chatgpt", authenticated: bool }` | Legacy authentication event. |
| `loginChatGptComplete` (legacy) | `{ loginId, success, error? }` | Signals end of the interactive OAuth flow started by `loginChatGpt`. |
| `sessionConfigured` (legacy) | `SessionConfiguredNotification` | Historic analog to `codex/event/session_configured`; provided for backward compatibility. |

Treat the legacy notifications as optional—new flows should prefer the event stream and `account/*` endpoints.

## 9. Error Handling

- `-32600` (`INVALID_REQUEST_ERROR_CODE`): malformed or unexpected request, including method calls issued before `initialized`.
- `-32603` (`INTERNAL_ERROR_CODE`): server-side failure (serialization, internal panic). Retry after logging the message.
- All errors include a human-readable `message` and may include `data`.
- The server logs additional detail on stderr; forward this to the IDE logs for diagnostics.

If your client cannot meet a server request (e.g., approval timeout), respond with an error in the JSON-RPC sense:

```json
{
  "id": 42,
  "error": { "code": 5000, "message": "User dismissed approval dialog." }
}
```

The app server currently treats inability to deserialize a response as denial; explicit JSON-RPC errors are surfaced in the Codex log.

## 10. Schema Tooling

Codex ships code generators that emit up-to-date TypeScript and JSON Schema bundles for this protocol:

```sh
codex generate-ts --out ./protocol-types   # TypeScript
codex generate-json --out ./protocol-schema   # JSON Schema bundle
```

Both commands walk the same type lists used in this document (`ClientRequest`, `ServerRequest`, `ServerNotification`, JSON-RPC envelopes, and the event payloads from `codex_protocol`). Regenerate them whenever Codex is updated and vendor the results into your plugin to keep static typing in sync.

## 11. Interop Notes from the TypeScript SDK

The community SDK in `sdk/typescript` wraps `codex exec --experimental-json`, which speaks a **different** event dialect (`ThreadEvent`). However, its higher-level abstractions mirror app-server semantics:

- `Thread.runStreamed()` parses JSONL into `ThreadEvent` unions (`thread.started`, `item.started`, `turn.completed`, ...).
- Agent items map cleanly onto `codex/event` payloads:
  - `ThreadItem.type === "command_execution"` ↔ `exec_command_*` and approvals.
  - `ThreadItem.type === "todo_list"` ↔ `plan_update`.
- The SDK expects every JSON line to deserialize; your client should do the same for app-server notifications and treat unknown events as forward-compatible extensions.

Although the SDK targets `codex exec`, its parsing strategies (strict JSON decoding, streaming iteration, tracking thread/item ids) are directly applicable to the app-server stream.

## 12. Quick Reference (Cheat Sheet)

- **Start server:** `codex app-server [-c key=value ...]`
- **Handshake:** `initialize` → response → notification `initialized`
- **Create session:** `newConversation` (save `conversationId`)
- **Subscribe:** `addConversationListener` → keep `subscriptionId`
- **Event loop:** handle `codex/event/*`, process approvals, watch for `task_complete`
- **Send input:** `sendUserMessage` or `sendUserTurn`
- **Terminate:** `removeConversationListener`, optionally `interruptConversation`
- **Approvals:** respond with `{"decision": "<review_decision>"}`; default to `"denied"` if unsure
- **Regenerate schemas:** `codex generate-ts`, `codex generate-json`

Armed with this mapping, you can build a JetBrains client that speaks the same JSON-RPC protocol as Codex’s first-party integrations while retaining flexibility to layer IDE-specific UX on top of the raw event stream.

---

*Maintainer note:* revisit this document whenever the `codex-rs/app-server-protocol` crate gains new methods or when event semantics change. Keeping the JetBrains plugin in lockstep prevents subtle drift from Codex’s internal tooling.
