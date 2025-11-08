package com.jetbrains.codex.protocol

import kotlinx.serialization.json.*
import kotlin.math.roundToLong
import java.util.Base64

sealed class CodexEvent {
    data class ThreadStarted(
        val threadId: String,
        val model: String?,
        val modelProvider: String?,
        val rolloutPath: String?,
        val sessionId: String?
    ) : CodexEvent()
    
    data class TaskStarted(
        val modelContextWindow: Int,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class TaskComplete(
        val lastAgentMessage: String?,
        val threadId: String,
        val turnId: String?,
        val status: String?
    ) : CodexEvent()
    
    data class TurnAborted(
        val reason: String,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class AgentMessage(
        val message: String,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class AgentMessageDelta(
        val delta: String,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class AgentReasoning(
        val content: String,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class AgentReasoningDelta(
        val delta: String,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class PlanUpdate(
        val explanation: String?,
        val plan: List<PlanStep>,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class McpToolCallBegin(
        val callId: String,
        val tool: String,
        val server: String,
        val arguments: JsonObject,
        val threadId: String
    ) : CodexEvent()
    
    data class McpToolCallEnd(
        val callId: String,
        val result: JsonElement?,
        val error: String?,
        val threadId: String
    ) : CodexEvent()
    
    data class ExecCommandBegin(
        val callId: String,
        val command: List<String>,
        val cwd: String,
        val threadId: String
    ) : CodexEvent()
    
    data class ExecCommandOutputDelta(
        val callId: String,
        val stream: String,
        val chunk: String,
        val threadId: String
    ) : CodexEvent()
    
    data class ExecCommandEnd(
        val callId: String,
        val exitCode: Int,
        val duration: Long?,
        val threadId: String
    ) : CodexEvent()
    
    data class ApplyPatchApprovalRequest(
        val callId: String,
        val fileChanges: JsonObject,
        val reason: String?,
        val threadId: String
    ) : CodexEvent()
    
    data class PatchApplyBegin(
        val callId: String,
        val autoApproved: Boolean,
        val threadId: String
    ) : CodexEvent()
    
    data class PatchApplyEnd(
        val callId: String,
        val success: Boolean,
        val threadId: String
    ) : CodexEvent()
    
    data class WebSearchBegin(
        val callId: String,
        val query: String,
        val threadId: String
    ) : CodexEvent()
    
    data class WebSearchEnd(
        val callId: String,
        val threadId: String
    ) : CodexEvent()
    
    data class TokenCount(
        val totalTokenUsage: Int,
        val lastTokenUsage: Int,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class Error(
        val message: String,
        val details: String?,
        val threadId: String,
        val turnId: String?
    ) : CodexEvent()
    
    data class Warning(
        val message: String,
        val threadId: String
    ) : CodexEvent()
    
    data class RateLimitsUpdated(
        val limits: JsonObject
    ) : CodexEvent()

    data class Unknown(
        val method: String,
        val params: JsonObject
    ) : CodexEvent()
}

data class PlanStep(
    val step: String,
    val status: String
)

private val durationStringPattern = Regex(
    pattern = "^([0-9]+(?:\\.[0-9]+)?)\\s*(ms|millis|millisecond|milliseconds|s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|us|micro|micros|microsecond|microseconds|ns|nano|nanos|nanosecond|nanoseconds)$",
    options = setOf(RegexOption.IGNORE_CASE)
)

private val durationDirectKeyOrder = listOf(
    "millis",
    "milliseconds",
    "durationMs",
    "durationMillis",
    "ms",
    "value",
    "raw",
    "totalMs",
    "totalMillis"
)

private val durationDirectKeyLookup = durationDirectKeyOrder.toSet()

private val durationTextKeyOrder = listOf(
    "approximate",
    "pretty",
    "human",
    "humanReadable",
    "display"
)

private val durationTextKeyLookup = durationTextKeyOrder.toSet()

private val durationPartKeys = setOf(
    "seconds", "second", "secs", "sec", "s",
    "minutes", "minute", "mins", "min", "m",
    "hours", "hour", "hrs", "hr", "h",
    "nanos", "nano", "ns",
    "microseconds", "microsecond", "micros", "micro", "us"
)

private fun parseItemCreated(params: JsonObject, threadId: String): CodexEvent? {
    val item = params["item"]?.jsonObject ?: return null
    val turnId = params.stringOrNull("turnId") ?: item.stringOrNull("turnId")
    val type = item.stringOrNull("type")?.lowercase()
    val purpose = item.stringOrNull("purpose")?.lowercase()
    val text = item.stringOrNull("text")
        ?: extractTextFromContent(item["content"])
        ?: item.stringOrNull("value")
    val delta = item.stringOrNull("textDelta")
        ?: extractTextFromContent(item["textDelta"])
        ?: item.stringOrNull("delta")
        ?: extractTextFromContent(item["delta"])

    return when {
        isReasoning(type, purpose) -> when {
            !text.isNullOrBlank() -> CodexEvent.AgentReasoning(text, threadId, turnId)
            !delta.isNullOrBlank() -> CodexEvent.AgentReasoningDelta(delta, threadId, turnId)
            else -> null
        }

        isPlan(type) -> {
            val stepsArray = item["steps"]?.jsonArray ?: item["plan"]?.jsonArray
            val planSteps = stepsArray?.toPlanSteps() ?: emptyList()
            CodexEvent.PlanUpdate(
                explanation = item.stringOrNull("explanation"),
                plan = planSteps,
                threadId = threadId,
                turnId = turnId
            )
        }

        else -> when {
            !text.isNullOrBlank() -> CodexEvent.AgentMessage(text, threadId, turnId)
            !delta.isNullOrBlank() -> CodexEvent.AgentMessageDelta(delta, threadId, turnId)
            else -> null
        }
    }
}

private fun parseItemDelta(params: JsonObject, threadId: String): CodexEvent? {
    val item = params["item"]?.jsonObject ?: params
    val turnId = params.stringOrNull("turnId") ?: item.stringOrNull("turnId")
    val type = item.stringOrNull("type")?.lowercase()
    val purpose = item.stringOrNull("purpose")?.lowercase()
    val delta = item.stringOrNull("textDelta")
        ?: extractTextFromContent(item["textDelta"])
        ?: item.stringOrNull("delta")
        ?: extractTextFromContent(item["delta"])
        ?: params.stringOrNull("delta")

    if (delta.isNullOrBlank()) return null

    return if (isReasoning(type, purpose)) {
        CodexEvent.AgentReasoningDelta(delta, threadId, turnId)
    } else {
        CodexEvent.AgentMessageDelta(delta, threadId, turnId)
    }
}

private fun isReasoning(type: String?, purpose: String?): Boolean {
    return type?.contains("reasoning") == true || purpose == "reasoning"
}

private fun isPlan(type: String?): Boolean {
    return type == "plan"
}

private fun JsonArray.toPlanSteps(): List<PlanStep> {
    return mapNotNull { element ->
        val obj = element.jsonObject
        val step = obj.stringOrNull("step") ?: obj.stringOrNull("title") ?: return@mapNotNull null
        val status = obj.stringOrNull("status") ?: "pending"
        PlanStep(step = step, status = status)
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.asPrimitiveString()

private fun JsonElement.asPrimitiveString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun decodeBase64(encoded: String): String {
    return try {
        if (encoded.isEmpty()) return ""
        val decoded = Base64.getDecoder().decode(encoded)
        String(decoded, Charsets.UTF_8)
    } catch (e: Exception) {
        encoded
    }
}

private fun extractTextFromContent(element: JsonElement?): String? {
    return when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> {
            element.stringOrNull("text")
                ?: element.stringOrNull("textDelta")
                ?: element.stringOrNull("delta")
                ?: element.stringOrNull("value")
                ?: extractTextFromContent(element["content"])
        }
        is JsonArray -> {
            val parts = element.mapNotNull { extractTextFromContent(it) }
            if (parts.isEmpty()) null else parts.joinToString(separator = "")
        }
        else -> null
    }
}

private fun parseDurationMillis(element: JsonElement?): Long? = when (element) {
    null -> null
    is JsonPrimitive -> parseDurationPrimitive(element)
    is JsonObject -> parseDurationObject(element)
    is JsonArray -> parseDurationArray(element)
    else -> null
}

private fun parseDurationPrimitive(primitive: JsonPrimitive): Long? {
    primitive.longOrNull?.let { return it }
    primitive.doubleOrNull?.let { return it.roundToLong() }

    val content = primitive.contentOrNull?.let { sanitizeDurationString(it) } ?: return null
    if (content.isEmpty()) return null

    content.toLongOrNull()?.let { return it }
    content.toDoubleOrNull()?.let { return it.roundToLong() }

    val match = durationStringPattern.matchEntire(content)
    if (match != null) {
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val multiplier = when (unit) {
            "ms", "millis", "millisecond", "milliseconds" -> 1.0
            "s", "sec", "secs", "second", "seconds" -> 1_000.0
            "m", "min", "mins", "minute", "minutes" -> 60_000.0
            "h", "hr", "hrs", "hour", "hours" -> 3_600_000.0
            "us", "micro", "micros", "microsecond", "microseconds" -> 0.001
            "ns", "nano", "nanos", "nanosecond", "nanoseconds" -> 0.000001
            else -> 1.0
        }
        return (value * multiplier).roundToLong()
    }

    return null
}

private fun parseDurationObject(obj: JsonObject): Long? {
    for (key in durationDirectKeyOrder) {
        val parsed = parseDurationMillis(obj[key])
        if (parsed != null) return parsed
    }

    val contributions = mutableListOf<Double>()
    fun addContribution(value: Double?) {
        if (value != null && !value.isNaN() && !value.isInfinite()) {
            contributions += value
        }
    }

    addContribution(obj.doubleValue("seconds", "second", "secs", "sec", "s")?.times(1_000.0))
    addContribution(obj.doubleValue("minutes", "minute", "mins", "min", "m")?.times(60_000.0))
    addContribution(obj.doubleValue("hours", "hour", "hrs", "hr", "h")?.times(3_600_000.0))
    addContribution(obj.doubleValue("nanos", "nano", "ns")?.div(1_000_000.0))
    addContribution(obj.doubleValue("microseconds", "microsecond", "micros", "micro", "us")?.div(1_000.0))

    if (contributions.isNotEmpty()) {
        return contributions.sum().roundToLong()
    }

    for (key in durationTextKeyOrder) {
        val parsed = parseDurationMillis(obj[key])
        if (parsed != null) return parsed
    }

    for ((key, value) in obj) {
        if (key in durationDirectKeyLookup || key in durationPartKeys || key in durationTextKeyLookup) continue
        val parsed = parseDurationMillis(value)
        if (parsed != null) return parsed
    }

    return null
}

private fun parseDurationArray(array: JsonArray): Long? {
    for (value in array) {
        val parsed = parseDurationMillis(value)
        if (parsed != null) return parsed
    }
    return null
}

private fun sanitizeDurationString(raw: String): String =
    raw.trim().trimStart { it == '~' }.trimEnd { it == '+' }.trim()

private fun JsonObject.doubleValue(vararg keys: String): Double? {
    for (key in keys) {
        val element = this[key]
        if (element is JsonPrimitive) {
            element.doubleOrNull?.let { return it }
            element.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

fun parseCodexEvent(notification: JsonRpcNotification): CodexEvent {
    val method = notification.method
    val params = notification.params
    val msg = params["msg"]?.jsonObject

    val threadId = params.stringOrNull("threadId")
        ?: params["thread"]?.jsonObject?.stringOrNull("id")
        ?: msg?.stringOrNull("threadId")
        ?: msg?.get("thread")?.jsonObject?.stringOrNull("id")
        ?: ""

    return when {
        method == "thread/started" -> {
            val threadObj = params["thread"]?.jsonObject ?: params
            val id = threadObj.stringOrNull("id") ?: threadId
            CodexEvent.ThreadStarted(
                threadId = id,
                model = threadObj.stringOrNull("model"),
                modelProvider = threadObj.stringOrNull("modelProvider"),
                rolloutPath = threadObj.stringOrNull("rolloutPath"),
                sessionId = threadObj.stringOrNull("sessionId")
            )
        }

        method == "turn/started" -> {
            val turn = params["turn"]?.jsonObject
            val turnId = turn?.stringOrNull("id") ?: params.stringOrNull("turnId")
            val ctx = turn?.get("modelContextWindow")?.jsonPrimitive?.intOrNull ?: 0
            CodexEvent.TaskStarted(
                modelContextWindow = ctx,
                threadId = threadId,
                turnId = turnId
            )
        }

        method == "turn/completed" -> {
            val turn = params["turn"]?.jsonObject ?: params
            val turnId = turn.stringOrNull("id") ?: params.stringOrNull("turnId")
            val status = turn.stringOrNull("status") ?: params.stringOrNull("status")
            val lastMessage = turn.stringOrNull("lastMessage") ?: params.stringOrNull("lastMessage")
            CodexEvent.TaskComplete(
                lastAgentMessage = lastMessage,
                threadId = threadId,
                turnId = turnId,
                status = status
            )
        }

        method == "item/created" -> {
            parseItemCreated(params, threadId)
                ?: CodexEvent.Unknown(method, params)
        }

        method == "item/delta" -> {
            parseItemDelta(params, threadId)
                ?: CodexEvent.Unknown(method, params)
        }

        method == "account/rateLimits/updated" -> {
            CodexEvent.RateLimitsUpdated(
                limits = params
            )
        }

        method == "sessionConfigured" -> {
            val payload = msg ?: params
            CodexEvent.ThreadStarted(
                threadId = threadId,
                model = payload.stringOrNull("model"),
                modelProvider = payload.stringOrNull("modelProvider"),
                rolloutPath = payload.stringOrNull("rolloutPath"),
                sessionId = payload.stringOrNull("sessionId")
            )
        }

        method.startsWith("codex/event/") -> {
            val eventType = method.removePrefix("codex/event/")
            val payload = msg ?: params

            when (eventType) {
                "session_configured" -> CodexEvent.ThreadStarted(
                    threadId = threadId,
                    model = payload.stringOrNull("model"),
                    modelProvider = payload.stringOrNull("modelProvider"),
                    rolloutPath = payload.stringOrNull("rolloutPath"),
                    sessionId = payload.stringOrNull("sessionId")
                )

                "task_started" -> CodexEvent.TaskStarted(
                    modelContextWindow = payload["modelContextWindow"]?.jsonPrimitive?.intOrNull ?: 0,
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "task_complete" -> CodexEvent.TaskComplete(
                    lastAgentMessage = payload.stringOrNull("lastAgentMessage"),
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId"),
                    status = payload.stringOrNull("status")
                )

                "turn_aborted" -> CodexEvent.TurnAborted(
                    reason = payload.stringOrNull("reason") ?: "unknown",
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "agent_message" -> CodexEvent.AgentMessage(
                    message = payload.stringOrNull("message") ?: "",
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "agent_message_delta" -> CodexEvent.AgentMessageDelta(
                    delta = payload.stringOrNull("delta") ?: "",
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "agent_reasoning" -> CodexEvent.AgentReasoning(
                    content = payload.stringOrNull("content") ?: "",
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "agent_reasoning_delta" -> CodexEvent.AgentReasoningDelta(
                    delta = payload.stringOrNull("delta") ?: "",
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "plan_update" -> CodexEvent.PlanUpdate(
                    explanation = payload.stringOrNull("explanation"),
                    plan = payload["plan"]?.jsonArray?.map { step ->
                        val stepObj = step.jsonObject
                        PlanStep(
                            step = stepObj["step"]?.jsonPrimitive?.content ?: "",
                            status = stepObj["status"]?.jsonPrimitive?.content ?: "pending"
                        )
                    } ?: emptyList(),
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "mcp_tool_call_begin" -> CodexEvent.McpToolCallBegin(
                    callId = payload.stringOrNull("callId") ?: "",
                    tool = payload.stringOrNull("tool") ?: "",
                    server = payload.stringOrNull("server") ?: "",
                    arguments = payload["arguments"]?.jsonObject ?: buildJsonObject {},
                    threadId = threadId
                )

                "mcp_tool_call_end" -> CodexEvent.McpToolCallEnd(
                    callId = payload.stringOrNull("callId") ?: "",
                    result = payload["result"],
                    error = payload.stringOrNull("error"),
                    threadId = threadId
                )

                "exec_command_begin" -> CodexEvent.ExecCommandBegin(
                    callId = payload.stringOrNull("callId") ?: "",
                    command = payload["command"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    cwd = payload.stringOrNull("cwd") ?: "",
                    threadId = threadId
                )

                "exec_command_output_delta" -> CodexEvent.ExecCommandOutputDelta(
                    callId = payload.stringOrNull("callId") ?: "",
                    stream = payload.stringOrNull("stream") ?: "stdout",
                    chunk = decodeBase64(payload.stringOrNull("chunk") ?: ""),
                    threadId = threadId
                )

                "exec_command_end" -> CodexEvent.ExecCommandEnd(
                    callId = payload.stringOrNull("callId") ?: "",
                    exitCode = payload["exitCode"]?.jsonPrimitive?.intOrNull ?: -1,
                    duration = parseDurationMillis(payload["duration"]),
                    threadId = threadId
                )

                "apply_patch_approval_request" -> CodexEvent.ApplyPatchApprovalRequest(
                    callId = payload.stringOrNull("callId") ?: "",
                    fileChanges = payload["fileChanges"]?.jsonObject ?: buildJsonObject {},
                    reason = payload.stringOrNull("reason"),
                    threadId = threadId
                )

                "patch_apply_begin" -> CodexEvent.PatchApplyBegin(
                    callId = payload.stringOrNull("callId") ?: "",
                    autoApproved = payload["autoApproved"]?.jsonPrimitive?.booleanOrNull ?: false,
                    threadId = threadId
                )

                "patch_apply_end" -> CodexEvent.PatchApplyEnd(
                    callId = payload.stringOrNull("callId") ?: "",
                    success = payload["success"]?.jsonPrimitive?.booleanOrNull ?: false,
                    threadId = threadId
                )

                "web_search_begin" -> CodexEvent.WebSearchBegin(
                    callId = payload.stringOrNull("callId") ?: "",
                    query = payload.stringOrNull("query") ?: "",
                    threadId = threadId
                )

                "web_search_end" -> CodexEvent.WebSearchEnd(
                    callId = payload.stringOrNull("callId") ?: "",
                    threadId = threadId
                )

                "token_count" -> {
                    val info = payload["info"] as? JsonObject
                    val total = info?.get("totalTokenUsage")?.jsonPrimitive?.intOrNull ?: 0
                    val last = info?.get("lastTokenUsage")?.jsonPrimitive?.intOrNull ?: 0
                    CodexEvent.TokenCount(
                        totalTokenUsage = total,
                        lastTokenUsage = last,
                        threadId = threadId,
                        turnId = payload.stringOrNull("turnId")
                    )
                }

                "error" -> CodexEvent.Error(
                    message = payload.stringOrNull("message") ?: "",
                    details = null,
                    threadId = threadId,
                    turnId = payload.stringOrNull("turnId")
                )

                "warning" -> CodexEvent.Warning(
                    message = payload.stringOrNull("message") ?: "",
                    threadId = threadId
                )

                else -> CodexEvent.Unknown(
                    method = notification.method,
                    params = params
                )
            }
        }

        else -> CodexEvent.Unknown(
            method = notification.method,
            params = notification.params
        )
    }
}
