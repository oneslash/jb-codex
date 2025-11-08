# Codex CLI Event Handling Architecture

This document describes how the Codex CLI app-server handles events and notifications through its JSON-RPC protocol, based on reverse engineering the reference implementation and the JetBrains plugin implementation.

## Table of Contents

1. [Protocol Overview](#protocol-overview)
2. [Message Types](#message-types)
3. [Event Categories](#event-categories)
4. [Event Lifecycle](#event-lifecycle)
5. [Implementation Architecture](#implementation-architecture)
6. [Event Parsing Strategy](#event-parsing-strategy)
7. [JetBrains Plugin Implementation](#jetbrains-plugin-implementation)

---

## Protocol Overview

### Transport Layer

The Codex app-server uses **JSON-RPC over JSON Lines (JSONL)** via STDIN/STDOUT:

- **One JSON object per line** - each line is a complete message
- **No `"jsonrpc": "2.0"` field** - simplified JSON-RPC format
- **Bidirectional** - both client and server can initiate requests
- **Streaming** - events arrive as notifications during long-running operations

### Process Initialization

```bash
codex app-server [--extra-flags]
```

The binary:
1. Accepts JSON-RPC requests on STDIN
2. Emits JSON-RPC responses and notifications on STDOUT
3. Logs diagnostic information to STDERR

---

## Message Types

The protocol uses three fundamental message types:

### 1. Client Request → Server Response

**Request:**
```json
{
  "id": 1,
  "method": "thread/start",
  "params": {
    "model": "gpt-5-codex",
    "cwd": "/project"
  }
}
```

**Success Response:**
```json
{
  "id": 1,
  "result": {
    "thread": {
      "id": "thr_123",
      "modelProvider": "openai"
    }
  }
}
```

**Error Response:**
```json
{
  "id": 1,
  "error": {
    "code": -32603,
    "message": "Not initialized"
  }
}
```

### 2. Server Request → Client Response (Approvals)

The server can send requests that require a response:

**Server Request:**
```json
{
  "id": 42,
  "method": "execCommandApproval",
  "params": {
    "conversationId": "thr_123",
    "callId": "call-1",
    "command": ["git", "status"],
    "cwd": "/workspace",
    "reason": "Check git state"
  }
}
```

**Client Response:**
```json
{
  "id": 42,
  "result": {
    "decision": "approved"
  }
}
```

Possible decisions: `"approved"`, `"approved_for_session"`, `"denied"`, `"abort"`

### 3. Notifications (No Response Expected)

**Notification:**
```json
{
  "method": "thread/started",
  "params": {
    "thread": {
      "id": "thr_123",
      "modelProvider": "openai",
      "createdAt": 1730910000
    }
  }
}
```

Notifications have **no `id` field** and do not expect a response.

---

## Event Categories

The app-server emits events in several categories:

### Thread Lifecycle Events

| Method | Description | Key Fields |
|--------|-------------|-----------|
| `thread/started` | Thread initialized and ready | `thread.id`, `modelProvider`, `createdAt` |
| `sessionConfigured` (legacy) | Alternative thread start notification | `sessionId`, `model` |

### Turn Lifecycle Events

| Method | Description | Key Fields |
|--------|-------------|-----------|
| `turn/started` | User turn initiated | `turn.id`, `turn.status`, `threadId` |
| `turn/completed` | Turn finished | `status`, `turnId`, `threadId` |
| `item/created` | New item in the turn | `item.type`, `item.text/textDelta` |
| `item/delta` | Streaming update to item | `item.textDelta` or `delta` |

Turn completion statuses: `"completed"`, `"interrupted"`, `"failed"`

### Content Events (via `codex/event/*`)

These arrive with method names like `codex/event/<event_type>`:

| Event Type | Description | Payload |
|------------|-------------|---------|
| `session_configured` | Session setup complete | `model`, `sessionId`, `rolloutPath` |
| `task_started` | Turn processing begins | `modelContextWindow`, `turnId` |
| `task_complete` | Turn processing ends | `lastAgentMessage`, `status` |
| `turn_aborted` | Turn cancelled | `reason`: `"interrupted"` \| `"replaced"` \| `"review_ended"` |
| `agent_message` | Full agent text message | `message` |
| `agent_message_delta` | Streaming agent text | `delta` |
| `agent_reasoning` | Full reasoning content | `content` |
| `agent_reasoning_delta` | Streaming reasoning | `delta` |
| `plan_update` | Task plan updated | `plan`: array of steps, `explanation` |

### Tool Execution Events

| Event Type | Description | Payload |
|------------|-------------|---------|
| `exec_command_begin` | Command execution starts | `callId`, `command`, `cwd` |
| `exec_command_output_delta` | Streaming command output | `callId`, `stream` (`"stdout"` \| `"stderr"`), `chunk` |
| `exec_command_end` | Command execution completes | `callId`, `exitCode`, `duration` |
| `mcp_tool_call_begin` | MCP tool invoked | `callId`, `tool`, `server`, `arguments` |
| `mcp_tool_call_end` | MCP tool completed | `callId`, `result`, `error` |
| `web_search_begin` | Web search initiated | `callId`, `query` |
| `web_search_end` | Web search completed | `callId` |

### Patch Events

| Event Type | Description | Payload |
|------------|-------------|---------|
| `apply_patch_approval_request` | Approval requested for file changes | `callId`, `fileChanges`, `reason` |
| `patch_apply_begin` | Patch application starts | `callId`, `autoApproved` |
| `patch_apply_end` | Patch application completes | `callId`, `success` |

### Accounting Events

| Event Type | Description | Payload |
|------------|-------------|---------|
| `token_count` | Token usage update | `info.totalTokenUsage`, `info.lastTokenUsage` |
| `account/rateLimits/updated` | Rate limit snapshot | `rateLimits.primary.usedPercent`, `resetsAt` |

### Error & Warning Events

| Event Type | Description | Payload |
|------------|-------------|---------|
| `error` | Error occurred | `message`, `details` |
| `warning` | Warning issued | `message` |

---

## Event Lifecycle

### Initialization Sequence

```
Client                           Server
  |                               |
  |--initialize------------------>|
  |<-------------result-----------|
  |--initialized (notification)-->|
  |                               |
  [Ready for operations]
```

Must send `initialize` request, receive result, then send `initialized` notification before any other method calls.

### Thread and Turn Flow

```
Client                                      Server
  |                                          |
  |--thread/start-------------------------->|
  |<-------------result---------------------|
  |<-------------thread/started (notify)----|
  |                                          |
  |--turn/start---------------------------->|
  |<-------------result---------------------|
  |<-------------turn/started (notify)------|
  |<-------------item/created (notify)------|
  |<-------------item/delta (notify)--------|
  |<-------------codex/event/* (notify)-----|
  |<-------------turn/completed (notify)----|
  |                                          |
  [Turn complete, ready for next]
```

### Approval Flow

```
Client                                      Server
  |<-------------execCommandApproval--------|
  |           (server-initiated request)    |
  |--result: {decision: "approved"}-------->|
  |<-------------exec_command_begin---------|
  |<-------------exec_command_output_delta--|
  |<-------------exec_command_end-----------|
```

---

## Implementation Architecture

### Key Components

Based on the JetBrains plugin implementation:

#### 1. ProcessManager

Manages the `codex app-server` subprocess lifecycle:

- **Spawns process** with working directory and arguments
- **Captures STDOUT** as a flow of JSON Lines
- **Pipes STDERR** to IDE logs
- **Automatic restart** with exponential backoff (1s, 2s, 4s, 8s, 16s, max 30s)
- **Max restart attempts** to prevent infinite loops
- **State tracking**: `Stopped`, `Starting`, `Running`, `Restarting(attempt, delayMs)`, `Failed(error, restartIn)`

**Key Implementation Details:**
- Uses `ProcessBuilder` to spawn `codex app-server`
- Separate coroutines for stdout reading, stderr logging, and process monitoring
- Synchronized writes to stdin to prevent corruption
- Graceful shutdown with `destroyForcibly()`

#### 2. JsonRpcClient

Handles JSON-RPC framing, correlation, and multiplexing:

- **Request ID generation** - auto-incrementing counter
- **Request/response correlation** - maps `id` to `CompletableDeferred<JsonElement>`
- **Notification dispatch** - emits to `Channel<JsonRpcNotification>`
- **Approval request handling** - separate channel for server-initiated requests
- **Initialization guard** - ensures `initialize` handshake completes first

**Message Classification Logic:**
```kotlin
when {
    json.containsKey("id") && json.containsKey("result") -> {
        // Response to our request
        pendingRequests.remove(id)?.complete(result)
    }
    json.containsKey("id") && json.containsKey("error") -> {
        // Error response to our request
        pendingRequests.remove(id)?.completeExceptionally(JsonRpcException(error))
    }
    json.containsKey("id") && json.containsKey("method") -> {
        // Server-initiated request (approval)
        when (method) {
            "execCommandApproval", "applyPatchApproval" -> {
                approvalRequestChannel.send(ApprovalRequest(id, method, params))
            }
        }
    }
    json.containsKey("method") -> {
        // Notification (no id)
        notificationChannel.send(JsonRpcNotification(method, params))
    }
}
```

#### 3. EventRouter

Transforms raw JSON-RPC notifications into strongly-typed domain events:

**Core Responsibilities:**
- Parse notification method and params
- Extract `threadId` from multiple possible locations
- Distinguish between app-server v2 methods (`thread/*`, `turn/*`, `item/*`) and legacy `codex/event/*` methods
- Handle both full item content (`item/created`) and streaming deltas (`item/delta`)
- Parse complex duration formats (strings, objects with units, arrays)

**Event Discrimination Strategy:**

```kotlin
fun parseCodexEvent(notification: JsonRpcNotification): CodexEvent {
    val method = notification.method
    val params = notification.params
    val msg = params["msg"]?.jsonObject
    
    val threadId = params.stringOrNull("threadId")
        ?: params["thread"]?.jsonObject?.stringOrNull("id")
        ?: msg?.stringOrNull("threadId")
        ?: ""
    
    return when {
        method == "thread/started" -> /* parse thread started */
        method == "turn/started" -> /* parse turn started */
        method == "turn/completed" -> /* parse turn completed */
        method == "item/created" -> parseItemCreated(params, threadId)
        method == "item/delta" -> parseItemDelta(params, threadId)
        method.startsWith("codex/event/") -> {
            val eventType = method.removePrefix("codex/event/")
            when (eventType) {
                "task_started" -> /* ... */
                "exec_command_begin" -> /* ... */
                // ... all other event types
            }
        }
        method == "account/rateLimits/updated" -> /* ... */
        else -> CodexEvent.Unknown(method, params)
    }
}
```

#### 4. SessionRegistry

Tracks active threads and their states:

- **Session lifecycle**: `Created` → `Configured` → `Active` → `Completed`/`Interrupted`
- **Active turn tracking** - stores current `turnId` for interruption
- **Session events** - emits `SessionEvent` flow for UI updates
- **Archive support** - calls `thread/archive` and transitions to `Archived` state

---

## Event Parsing Strategy

### Handling `item/created` and `item/delta`

Items are discriminated unions that can represent:
- Agent response text
- Agent reasoning
- Task plans
- Images

**Type discrimination logic:**

```kotlin
private fun parseItemCreated(params: JsonObject, threadId: String): CodexEvent? {
    val item = params["item"]?.jsonObject ?: return null
    val type = item.stringOrNull("type")?.lowercase()
    val purpose = item.stringOrNull("purpose")?.lowercase()
    
    return when {
        isReasoning(type, purpose) -> {
            // Extract reasoning content
        }
        isPlan(type) -> {
            // Extract plan steps
        }
        else -> {
            // Default to agent message
        }
    }
}

private fun isReasoning(type: String?, purpose: String?): Boolean {
    return type?.contains("reasoning") == true || purpose == "reasoning"
}

private fun isPlan(type: String?): Boolean {
    return type == "plan"
}
```

**Text Extraction Strategy:**

The protocol sends text in various structures:

1. **Direct primitive**: `"text": "Hello"`
2. **Nested in content**: `"content": { "text": "Hello" }`
3. **Delta field**: `"textDelta": "Hello"`
4. **Array of parts**: `"content": [{ "text": "Hello" }, { "text": " world" }]`

Recursive extraction function:

```kotlin
private fun extractTextFromContent(element: JsonElement?): String? {
    return when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> {
            element.stringOrNull("text")
                ?: element.stringOrNull("textDelta")
                ?: element.stringOrNull("delta")
                ?: extractTextFromContent(element["content"])
        }
        is JsonArray -> {
            element.mapNotNull { extractTextFromContent(it) }.joinToString("")
        }
        else -> null
    }
}
```

### Duration Parsing

Durations can appear as:
- Millisecond integers: `1234`
- Strings with units: `"1.5s"`, `"250ms"`, `"~30s"`
- Objects with parts: `{ "seconds": 1, "nanos": 500000000 }`
- Objects with keys: `{ "durationMs": 1500 }`

The parser handles all formats with a priority order:

1. Direct numeric keys: `millis`, `milliseconds`, `durationMs`, `ms`
2. Part decomposition: `seconds`, `minutes`, `hours`, `nanos`, `microseconds`
3. Human-readable keys: `approximate`, `pretty`, `human`
4. Fallback: scan all keys recursively

---

## JetBrains Plugin Implementation

### Integration Points

#### Chat UI

Consumes events to update:
- **Message list** - renders `AgentMessage`, `AgentReasoning`
- **Streaming deltas** - appends `AgentMessageDelta`, `AgentReasoningDelta` to active message
- **Plan panel** - displays `PlanUpdate` as checklist
- **Status indicators** - shows turn state from `TaskStarted`, `TaskComplete`

#### Timeline Panel

Displays tool execution history:
- **Command cards** - from `ExecCommandBegin` + `ExecCommandOutputDelta` + `ExecCommandEnd`
- **Patch cards** - from `PatchApplyBegin` + `PatchApplyEnd`
- **MCP tool cards** - from `McpToolCallBegin` + `McpToolCallEnd`
- **Search cards** - from `WebSearchBegin` + `WebSearchEnd`

#### Approval Service

Handles server-initiated approval requests:
- Listens to `JsonRpcClient.approvalRequestChannel`
- Shows modal dialog with command/patch preview
- Calls `respondToApproval(requestId, decision)` with user choice
- Supports "approve for session" mode to auto-approve similar requests

#### Usage Tracker

Monitors token consumption:
- Parses `TokenCount` events for turn-level usage
- Listens to `RateLimitsUpdated` for quota windows
- Displays usage bar in header with reset timer

### Event Flow Diagram

```
ProcessManager.stdoutFlow
         |
         v
JsonRpcClient.processLine()
         |
         +-----> pendingRequests (responses)
         |
         +-----> approvalRequestChannel (approvals)
         |
         +-----> notificationChannel (events)
                        |
                        v
               EventRouter.parseCodexEvent()
                        |
                        v
                   CodexEvent
                        |
         +--------------+---------------+
         |              |               |
         v              v               v
    Chat Panel    Timeline Panel   Session Registry
```

### Error Handling

**Malformed JSON:**
- Logged to IDE diagnostics
- Line discarded, processing continues

**Unknown event types:**
- Wrapped in `CodexEvent.Unknown`
- Logged for debugging
- UI remains stable (forward compatibility)

**Protocol version mismatches:**
- Parser attempts both v2 and legacy formats
- Falls back to `Unknown` if both fail
- Allows graceful degradation

**Process crashes:**
- ProcessManager attempts restart with backoff
- SessionRegistry preserves state
- UI shows reconnecting indicator

---

## Implementation Checklist for JetBrains Plugin

### ✅ Completed Features

- [x] Process lifecycle management with restart
- [x] JSON-RPC client with request/response correlation
- [x] Notification channel multiplexing
- [x] Event router with strong typing
- [x] Thread and turn lifecycle tracking
- [x] Item parsing (messages, reasoning, plans)
- [x] Tool execution event handling
- [x] Approval request/response flow
- [x] Duration parsing
- [x] Token usage tracking
- [x] Rate limit monitoring
- [x] Error and warning events
- [x] Session state management

### Recommended Enhancements

- [ ] Persistent session history across plugin restarts
- [ ] Event replay from rollout files
- [ ] Rich diff viewer for patch approvals
- [ ] Approval policy configuration UI
- [ ] Event filtering and search in timeline
- [ ] Export conversation to markdown
- [ ] MCP server management UI
- [ ] Custom event handlers via extension points

---

## Protocol Compatibility Notes

### Multiple Event Formats

The app-server emits events in two formats for backward compatibility:

1. **V2 format** (current): `thread/started`, `turn/started`, `item/created`
2. **Legacy format**: `codex/event/session_configured`, `codex/event/task_started`

Clients should handle both formats. The JetBrains plugin parses both and normalizes to `CodexEvent` types.

### ThreadId Extraction

ThreadId may appear in multiple locations:

```json
{
  "method": "codex/event/task_started",
  "params": {
    "threadId": "thr_123"
  }
}
```

or:

```json
{
  "method": "thread/started",
  "params": {
    "thread": {
      "id": "thr_123"
    }
  }
}
```

or:

```json
{
  "method": "codex/event/agent_message",
  "params": {
    "msg": {
      "threadId": "thr_123"
    }
  }
}
```

Always check all possible locations with fallback chain.

### Approval Timeout

The server expects approval responses within a reasonable timeout (typically 60-120 seconds). If no response is received:
- Server defaults to `"denied"`
- Turn continues without executing the command/patch

Clients must:
- Respond promptly or show user dialog immediately
- Default to `"denied"` if unable to parse request
- Send explicit abort if user cancels

---

## Example Event Sequences

### Simple Question-Answer

```json
→ {"id":1,"method":"thread/start","params":{"model":"gpt-5-codex"}}
← {"id":1,"result":{"thread":{"id":"thr_1"}}}
← {"method":"thread/started","params":{"thread":{"id":"thr_1"}}}

→ {"id":2,"method":"turn/start","params":{"threadId":"thr_1","input":[{"type":"text","text":"What is 2+2?"}]}}
← {"id":2,"result":{"turn":{"id":"turn_1","status":"inProgress"}}}
← {"method":"turn/started","params":{"threadId":"thr_1","turn":{"id":"turn_1"}}}
← {"method":"item/created","params":{"threadId":"thr_1","turnId":"turn_1","item":{"type":"assistant","textDelta":"2"}}}
← {"method":"item/delta","params":{"threadId":"thr_1","turnId":"turn_1","delta":" + 2"}}
← {"method":"item/delta","params":{"threadId":"thr_1","turnId":"turn_1","delta":" = 4"}}
← {"method":"turn/completed","params":{"threadId":"thr_1","turnId":"turn_1","status":"completed"}}
```

### Command Execution with Approval

```json
→ {"id":3,"method":"turn/start","params":{"threadId":"thr_1","input":[{"type":"text","text":"Run git status"}]}}
← {"id":3,"result":{"turn":{"id":"turn_2"}}}
← {"method":"turn/started","params":{"threadId":"thr_1","turn":{"id":"turn_2"}}}

← {"id":100,"method":"execCommandApproval","params":{"conversationId":"thr_1","callId":"cmd_1","command":["git","status"],"cwd":"/project"}}
→ {"id":100,"result":{"decision":"approved"}}

← {"method":"codex/event/exec_command_begin","params":{"msg":{"callId":"cmd_1","command":["git","status"]},"threadId":"thr_1"}}
← {"method":"codex/event/exec_command_output_delta","params":{"msg":{"callId":"cmd_1","stream":"stdout","chunk":"On branch main\n"},"threadId":"thr_1"}}
← {"method":"codex/event/exec_command_end","params":{"msg":{"callId":"cmd_1","exitCode":0,"duration":"125ms"},"threadId":"thr_1"}}

← {"method":"turn/completed","params":{"threadId":"thr_1","turnId":"turn_2","status":"completed"}}
```

### Turn Interruption

```json
→ {"id":4,"method":"turn/start","params":{"threadId":"thr_1","input":[{"type":"text","text":"Long task"}]}}
← {"id":4,"result":{"turn":{"id":"turn_3"}}}
← {"method":"turn/started","params":{"threadId":"thr_1","turn":{"id":"turn_3"}}}

→ {"id":5,"method":"turn/interrupt","params":{"threadId":"thr_1","turnId":"turn_3"}}
← {"id":5,"result":{}}

← {"method":"codex/event/turn_aborted","params":{"msg":{"reason":"interrupted"},"threadId":"thr_1","turnId":"turn_3"}}
← {"method":"turn/completed","params":{"threadId":"thr_1","turnId":"turn_3","status":"interrupted"}}
```

---

## Best Practices

### For Client Implementations

1. **Always complete initialization handshake** before other operations
2. **Handle unknown event types gracefully** - log and continue
3. **Parse threadId from multiple locations** - protocol varies
4. **Default to deny on approval parse failures** - safety first
5. **Use backoff for process restarts** - prevent resource exhaustion
6. **Deduplicate events** if receiving both v2 and legacy formats
7. **Buffer notifications** to avoid overwhelming UI thread
8. **Provide cancel mechanism** for long-running turns
9. **Log protocol messages at debug level** for troubleshooting
10. **Validate state transitions** - don't start turn on inactive thread

### For Robust Event Handling

1. **Separate parsing from rendering** - EventRouter → Domain Events → UI Updates
2. **Accumulate streaming deltas** in temporary buffers, flush on item completion
3. **Track callId for correlating tool lifecycle** (begin → output → end)
4. **Parse durations defensively** - many formats exist
5. **Extract text recursively** - content can be deeply nested
6. **Preserve unknown fields** - helpful for debugging and forward compatibility

---

## Conclusion

The Codex CLI event system uses a straightforward JSON-RPC-over-JSONL protocol with:
- Clear message type discrimination (request/response/notification/approval)
- Rich event vocabulary covering lifecycle, content, tools, and accounting
- Backward compatibility via dual event formats
- Extensible with `Unknown` fallback for forward compatibility

The JetBrains plugin demonstrates a clean architecture for consuming these events:
- **ProcessManager** for subprocess lifecycle
- **JsonRpcClient** for protocol framing and correlation
- **EventRouter** for parsing into domain events
- **SessionRegistry** for state management
- **UI components** for rendering events

This architecture is **fully implementable in JetBrains IDEs** and provides all necessary hooks for a first-class IDE integration.

