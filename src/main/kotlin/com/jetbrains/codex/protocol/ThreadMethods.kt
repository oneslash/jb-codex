package com.jetbrains.codex.protocol

import kotlinx.serialization.json.*

data class ThreadRef(
    val id: String,
    val preview: String?,
    val modelProvider: String?,
    val createdAt: Long?
)

data class ThreadListResult(
    val data: List<ThreadRef>,
    val nextCursor: String?
)

data class TurnRef(
    val id: String,
    val status: String,
    val items: List<JsonElement>,
    val error: String?
)

suspend fun JsonRpcClient.startThread(
    model: String,
    cwd: String,
    approvalPolicy: String = "onRequest",
    sandbox: String = "workspaceWrite",
    baseInstructions: String? = null,
    developerInstructions: String? = null
): ThreadRef {
    val params = buildJsonObject {
        put("model", model)
        put("cwd", cwd)
        put("approvalPolicy", approvalPolicy)
        put("sandbox", sandbox)
        baseInstructions?.let { put("baseInstructions", it) }
        developerInstructions?.let { put("developerInstructions", it) }
    }

    val result = sendRequest("thread/start", params)
    return result.jsonObject["thread"]?.jsonObject?.toThreadRef()
        ?: throw JsonRpcException("Missing thread in thread/start response")
}

suspend fun JsonRpcClient.resumeThread(threadId: String): ThreadRef {
    val params = buildJsonObject { put("threadId", threadId) }
    val result = sendRequest("thread/resume", params)
    return result.jsonObject["thread"]?.jsonObject?.toThreadRef()
        ?: throw JsonRpcException("Missing thread in thread/resume response")
}

suspend fun JsonRpcClient.listThreads(
    cursor: String? = null,
    limit: Int? = null,
    modelProviders: List<String>? = null
): ThreadListResult {
    val params = buildJsonObject {
        cursor?.let { put("cursor", it) }
        limit?.let { put("limit", it) }
        modelProviders?.let {
            put("modelProviders", buildJsonArray {
                it.forEach { provider -> add(provider) }
            })
        }
    }

    val result = sendRequest("thread/list", params)
    val data = result.jsonObject["data"]?.jsonArray?.mapNotNull { element ->
        element.jsonObject.toThreadRef()
    } ?: emptyList()

    val nextCursor = result.jsonObject["nextCursor"]?.jsonPrimitive?.contentOrNull
    return ThreadListResult(data, nextCursor)
}

suspend fun JsonRpcClient.archiveThread(threadId: String) {
    val params = buildJsonObject { put("threadId", threadId) }
    sendRequest("thread/archive", params)
}

suspend fun JsonRpcClient.startTurn(
    threadId: String,
    text: String,
    attachments: List<String> = emptyList(),
    effort: String = "medium",
    summary: String = "auto",
    approvalPolicy: String? = null,
    sandbox: String? = null,
    cwd: String? = null,
    model: String? = null
): TurnRef {
    val params = buildJsonObject {
        put("threadId", threadId)
        put("input", buildInputItems(text, attachments))
        put("effort", effort)
        put("summary", summary)
        approvalPolicy?.let { put("approvalPolicy", it) }
        sandbox?.let { put("sandbox", it) }
        cwd?.let { put("cwd", it) }
        model?.let { put("model", it) }
    }

    val result = sendRequest("turn/start", params)
    return result.jsonObject["turn"]?.jsonObject?.toTurnRef()
        ?: throw JsonRpcException("Missing turn in turn/start response")
}

suspend fun JsonRpcClient.interruptTurn(threadId: String, turnId: String) {
    val params = buildJsonObject {
        put("threadId", threadId)
        put("turnId", turnId)
    }
    sendRequest("turn/interrupt", params)
}

suspend fun JsonRpcClient.listModels(): JsonElement {
    return sendRequest("model/list", buildJsonObject {})
}

suspend fun JsonRpcClient.accountLoginStart(
    type: String,
    apiKey: String? = null
): JsonElement {
    val params = buildJsonObject {
        put("type", type)
        apiKey?.let { put("apiKey", it) }
    }
    return sendRequest("account/login/start", params)
}

suspend fun JsonRpcClient.accountLoginCancel(loginId: String): JsonElement {
    val params = buildJsonObject { put("loginId", loginId) }
    return sendRequest("account/login/cancel", params)
}

suspend fun JsonRpcClient.accountLogout(): JsonElement {
    return sendRequest("account/logout", buildJsonObject {})
}

suspend fun JsonRpcClient.accountRead(refreshToken: Boolean = false): JsonElement {
    val params = buildJsonObject { put("refreshToken", refreshToken) }
    return sendRequest("account/read", params)
}

suspend fun JsonRpcClient.accountRateLimitsRead(): JsonElement {
    return sendRequest("account/rateLimits/read", buildJsonObject {})
}

suspend fun JsonRpcClient.execOneOffCommand(
    command: List<String>,
    cwd: String
): JsonElement {
    val params = buildJsonObject {
        put("command", buildJsonArray {
            command.forEach { add(it) }
        })
        put("cwd", cwd)
    }
    return sendRequest("execOneOffCommand", params)
}

suspend fun JsonRpcClient.fuzzyFileSearch(
    query: String,
    cwd: String,
    maxResults: Int = 20
): JsonElement {
    val params = buildJsonObject {
        put("query", query)
        put("cwd", cwd)
        put("maxResults", maxResults)
    }
    return sendRequest("fuzzyFileSearch", params)
}

suspend fun JsonRpcClient.gitDiffToRemote(
    cwd: String,
    remote: String = "origin",
    branch: String = "main"
): JsonElement {
    val params = buildJsonObject {
        put("cwd", cwd)
        put("remote", remote)
        put("branch", branch)
    }
    return sendRequest("gitDiffToRemote", params)
}

private fun JsonObject.toThreadRef(): ThreadRef {
    return ThreadRef(
        id = this["id"]?.jsonPrimitive?.content
            ?: throw JsonRpcException("thread id missing"),
        preview = this["preview"]?.jsonPrimitive?.contentOrNull,
        modelProvider = this["modelProvider"]?.jsonPrimitive?.contentOrNull,
        createdAt = this["createdAt"]?.jsonPrimitive?.longOrNull
    )
}

private fun JsonObject.toTurnRef(): TurnRef {
    val items = this["items"]?.jsonArray?.map { it } ?: emptyList()
    return TurnRef(
        id = this["id"]?.jsonPrimitive?.content
            ?: throw JsonRpcException("turn id missing"),
        status = this["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        items = items,
        error = this["error"]?.jsonPrimitive?.contentOrNull
    )
}

private fun buildInputItems(text: String, attachments: List<String>): JsonArray {
    return buildJsonArray {
        if (text.isNotBlank()) {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }

        attachments.forEach { path ->
            add(buildJsonObject {
                put("type", "localImage")
                put("path", path)
            })
        }
    }
}
