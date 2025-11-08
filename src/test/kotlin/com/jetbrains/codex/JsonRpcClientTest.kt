package com.jetbrains.codex

import com.jetbrains.codex.protocol.JsonRpcClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedWriter
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonRpcClientTest {
    private lateinit var scope: CoroutineScope
    private lateinit var outputFlow: MutableSharedFlow<String>
    private lateinit var writer: StringWriter
    private lateinit var bufferedWriter: BufferedWriter
    private lateinit var client: JsonRpcClient
    
    @BeforeEach
    fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        outputFlow = MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        writer = StringWriter()
        bufferedWriter = BufferedWriter(writer)
        client = JsonRpcClient(bufferedWriter, outputFlow, scope)
    }
    
    @AfterEach
    fun teardown() {
        scope.cancel()
    }
    
    @Test
    fun testInitializeFlow() = runBlocking {
        val responseJob = scope.launch {
            delay(50)
            outputFlow.emit("""{"id":1,"result":{"userAgent":"codex/test"}}""")
        }
        
        client.initialize()
        
        responseJob.join()
        
        val output = writer.toString()
        assertTrue(output.contains("\"method\":\"initialize\""))
        assertTrue(output.contains("\"method\":\"initialized\""))
    }
    
    @Test
    fun testSendRequest() = runBlocking {
        val responseJob = scope.launch {
            delay(50)
            outputFlow.emit("""{"id":1,"result":{"userAgent":"codex/test"}}""")
            delay(50)
            outputFlow.emit("""{"id":2,"result":{"thread":{"id":"thr-789"}}}""")
        }

        client.initialize()

        val result = withTimeout(1000) {
            client.sendRequest("thread/start", buildJsonObject {
                put("model", "gpt-4")
                put("cwd", "/test")
            })
        }
        
        responseJob.join()
        
        assertEquals("thr-789", result.jsonObject["thread"]?.jsonObject?.get("id")?.jsonPrimitive?.content)

        val output = writer.toString()
        assertTrue(output.contains("\"method\":\"thread/start\""))
        assertTrue(output.contains("\"model\":\"gpt-4\""))
    }
    
    @Test
    fun testNotificationHandling() = runBlocking {
        val notifications = mutableListOf<String>()
        
        val job = scope.launch {
            for (notification in client.notificationChannel) {
                notifications.add(notification.method)
                if (notifications.size >= 2) break
            }
        }
        
        outputFlow.emit("""{"method":"turn/started","params":{"threadId":"test","turn":{"id":"turn-1"}}}""")
        outputFlow.emit("""{"method":"turn/completed","params":{"threadId":"test","turn":{"id":"turn-1","status":"completed"}}}""")
        
        withTimeout(1000) {
            job.join()
        }
        
        assertEquals(2, notifications.size)
        assertEquals("turn/started", notifications[0])
        assertEquals("turn/completed", notifications[1])
    }
    
    @Test
    fun testApprovalRequestHandling() = runBlocking {
        val approvals = mutableListOf<Int>()
        
        val job = scope.launch {
            for (approval in client.approvalRequestChannel) {
                approvals.add(approval.id)
                break
            }
        }
        
        outputFlow.emit("""{"id":42,"method":"execCommandApproval","params":{"command":["git","status"]}}""")
        
        withTimeout(1000) {
            job.join()
        }
        
        assertEquals(1, approvals.size)
        assertEquals(42, approvals[0])
    }
}
