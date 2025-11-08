package com.jetbrains.codex

import com.jetbrains.codex.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.BufferedWriter
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationTest {
    
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
    fun testCompleteConversationFlow() = runBlocking {
        val events = mutableListOf<CodexEvent>()
        
        val eventCollectorJob = scope.launch {
            for (notification in client.notificationChannel) {
                val event = parseCodexEvent(notification)
                events.add(event)
                if (event is CodexEvent.TaskComplete) break
            }
        }
        
        scope.launch {
            delay(50)
            outputFlow.emit("""{"id":1,"result":{"userAgent":"codex/1.0"}}""")
            
            delay(50)
            outputFlow.emit("""{"id":2,"result":{"thread":{"id":"thr-123"}}}""")
            
            delay(50)
            outputFlow.emit("""{"method":"thread/started","params":{"thread":{"id":"thr-123","model":"gpt-4"}}}""")
            
            delay(50)
            outputFlow.emit("""{"id":3,"result":{"turn":{"id":"turn-456","status":"inProgress","items":[]}}}""")
            
            delay(50)
            outputFlow.emit("""{"method":"turn/started","params":{"threadId":"thr-123","turn":{"id":"turn-456"}}}""")
            
            delay(50)
            outputFlow.emit("""{"method":"item/created","params":{"threadId":"thr-123","turnId":"turn-456","item":{"type":"assistant","text":"Hello!"}}}""")
            
            delay(50)
            outputFlow.emit("""{"method":"turn/completed","params":{"threadId":"thr-123","turn":{"id":"turn-456","status":"completed"}}}""")
        }

        client.initialize()

        val thread = client.startThread("gpt-4", "/test")
        assertEquals("thr-123", thread.id)
        
        client.startTurn(thread.id, "Hi there")
        
        withTimeout(5000) {
            eventCollectorJob.join()
        }
        
        assertTrue(events.any { it is CodexEvent.ThreadStarted })
        assertTrue(events.any { it is CodexEvent.TaskStarted })
        assertTrue(events.any { it is CodexEvent.AgentMessage })
        assertTrue(events.any { it is CodexEvent.TaskComplete })
        
        val output = writer.toString()
        assertTrue(output.contains("\"method\":\"initialize\""))
        assertTrue(output.contains("\"method\":\"thread/start\""))
        assertTrue(output.contains("\"method\":\"turn/start\""))
    }
    
    @Disabled("Requires real codex binary in PATH")
    @Test
    fun testRealCodexServer() = runBlocking {
        val processBuilder = ProcessBuilder("codex", "app-server")
        val process = processBuilder.start()
        
        try {
            val realWriter = BufferedWriter(process.outputStream.writer())
            val realFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
            
            scope.launch(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        realFlow.emit(line)
                    }
                }
            }
            
            val realClient = JsonRpcClient(realWriter, realFlow, scope)
            
            realClient.initialize()
            println("✓ Initialized")
            
            val thread = realClient.startThread(
                model = "gpt-4",
                cwd = System.getProperty("user.dir")
            )
            println("✓ Thread created: ${thread.id}")
            
            val events = mutableListOf<CodexEvent>()
            val eventJob = scope.launch {
                for (notification in realClient.notificationChannel) {
                    val event = parseCodexEvent(notification)
                    events.add(event)
                    println("Event: ${event::class.simpleName}")
                    
                    if (event is CodexEvent.TaskComplete) break
                }
            }
            
            realClient.startTurn(thread.id, "Say hello")
            
            withTimeout(30000) {
                eventJob.join()
            }
            
            assertTrue(events.any { it is CodexEvent.ThreadStarted })
            assertTrue(events.any { it is CodexEvent.TaskComplete })
            
            println("✓ Integration test passed with ${events.size} events")
            
        } finally {
            process.destroyForcibly()
        }
    }
}
