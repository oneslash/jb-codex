package com.jetbrains.codex.protocol

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JsonRpcClient(
    private val writer: BufferedWriter,
    outputFlow: SharedFlow<String>,
    private val scope: CoroutineScope
) {
    private val log = logger<JsonRpcClient>()
    private val requestIdCounter = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    
    private val _notificationChannel = Channel<JsonRpcNotification>(Channel.UNLIMITED)
    val notificationChannel: Channel<JsonRpcNotification> = _notificationChannel
    
    private val _approvalRequestChannel = Channel<ApprovalRequest>(Channel.UNLIMITED)
    val approvalRequestChannel: Channel<ApprovalRequest> = _approvalRequestChannel
    
    private val _initialized = CompletableDeferred<Unit>()
    val isInitialized: Boolean get() = _initialized.isCompleted

    init {
        scope.launch {
            outputFlow.collect { line ->
                processLine(line)
            }
        }
    }

    suspend fun awaitInitialized() {
        _initialized.await()
    }
    
    private fun processLine(line: String) {
        try {
            val json = Json.parseToJsonElement(line).jsonObject
            
            when {
                json.containsKey("id") && json.containsKey("result") -> {
                    val id = json["id"]?.jsonPrimitive?.int ?: return
                    val result = json["result"] ?: JsonNull
                    pendingRequests.remove(id)?.complete(result)
                }
                json.containsKey("id") && json.containsKey("error") -> {
                    val id = json["id"]?.jsonPrimitive?.int ?: return
                    val error = json["error"]
                    pendingRequests.remove(id)?.completeExceptionally(
                        JsonRpcException(error.toString())
                    )
                }
                json.containsKey("id") && json.containsKey("method") -> {
                    val id = json["id"]?.jsonPrimitive?.int ?: return
                    val method = json["method"]?.jsonPrimitive?.content ?: return
                    val params = json["params"]?.jsonObject
                    
                    when (method) {
                        "execCommandApproval", "applyPatchApproval" -> {
                            scope.launch {
                                _approvalRequestChannel.send(
                                    ApprovalRequest(id, method, params ?: buildJsonObject {})
                                )
                            }
                        }
                    }
                }
                json.containsKey("method") -> {
                    val method = json["method"]?.jsonPrimitive?.content ?: return
                    val params = json["params"]?.jsonObject
                    
                    scope.launch {
                        _notificationChannel.send(
                            JsonRpcNotification(method, params ?: buildJsonObject {})
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to parse JSON-RPC message: $line", e)
        }
    }
    
    suspend fun initialize() {
        if (_initialized.isCompleted) {
            log.warn("Already initialized, skipping")
            return
        }

        val result = sendRequestInternal("initialize", buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", "jetbrains-codex")
                put("title", "JetBrains Codex Plugin")
                put("version", "0.1.0")
            })
        })

        sendNotification("initialized", buildJsonObject {})
        _initialized.complete(Unit)
    }
    
    suspend fun sendRequest(method: String, params: JsonObject): JsonElement {
        // Ensure we're initialized before sending requests
        if (!_initialized.isCompleted) {
            throw IllegalStateException("JsonRpcClient not initialized. Call initialize() first.")
        }
        return sendRequestInternal(method, params)
    }

    private suspend fun sendRequestInternal(method: String, params: JsonObject): JsonElement {
        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[id] = deferred

        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }

        writeMessage(message)

        return withTimeout(30000) {
            deferred.await()
        }
    }
    
    suspend fun sendNotification(method: String, params: JsonObject) {
        val message = buildJsonObject {
            put("method", method)
            put("params", params)
        }
        writeMessage(message)
    }
    
    suspend fun respondToApproval(requestId: Int, decision: String) {
        val response = buildJsonObject {
            put("id", requestId)
            put("result", buildJsonObject {
                put("decision", decision)
            })
        }
        writeMessage(response)
    }
    
    private suspend fun writeMessage(message: JsonObject) {
        withContext(Dispatchers.IO) {
            synchronized(writer) {
                writer.write(message.toString())
                writer.newLine()
                writer.flush()
            }
        }
    }
}

data class JsonRpcNotification(
    val method: String,
    val params: JsonObject
)

data class ApprovalRequest(
    val id: Int,
    val method: String,
    val params: JsonObject
)

class JsonRpcException(message: String) : Exception(message)











