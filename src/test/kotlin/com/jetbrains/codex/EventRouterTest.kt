package com.jetbrains.codex

import com.jetbrains.codex.protocol.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventRouterTest {
    
    @Test
    fun testParseThreadStarted() {
        val notification = JsonRpcNotification(
            method = "thread/started",
            params = buildJsonObject {
                put("thread", buildJsonObject {
                    put("id", "thr-123")
                    put("model", "gpt-4")
                    put("modelProvider", "openai")
                })
            }
        )

        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.ThreadStarted)
        assertEquals("thr-123", event.threadId)
        assertEquals("gpt-4", event.model)
        assertEquals("openai", event.modelProvider)
    }

    @Test
    fun testParseLegacySessionConfigured() {
        val notification = JsonRpcNotification(
            method = "codex/event/session_configured",
            params = buildJsonObject {
                put("threadId", "test-123")
                put("msg", buildJsonObject {
                    put("sessionId", "session-456")
                    put("model", "gpt-4")
                    put("rolloutPath", "/tmp/rollout")
                })
            }
        )
        
        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.ThreadStarted)
        assertEquals("session-456", event.sessionId)
        assertEquals("gpt-4", event.model)
        assertEquals("/tmp/rollout", event.rolloutPath)
        assertEquals("test-123", event.threadId)
    }
    
    @Test
    fun testParseAgentMessage() {
        val notification = JsonRpcNotification(
            method = "codex/event/agent_message",
            params = buildJsonObject {
                put("threadId", "test-123")
                put("msg", buildJsonObject {
                    put("message", "Hello from agent")
                    put("turnId", "turn-1")
                })
            }
        )
        
        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.AgentMessage)
        assertEquals("Hello from agent", event.message)
        assertEquals("test-123", event.threadId)
        assertEquals("turn-1", event.turnId)
    }

    @Test
    fun testParseItemCreatedWithStructuredContent() {
        val notification = JsonRpcNotification(
            method = "item/created",
            params = buildJsonObject {
                put("threadId", "thr-structured")
                put("turnId", "turn-structured")
                put("item", buildJsonObject {
                    put("type", "assistant_message")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", "Hello, ")
                        })
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", "world!")
                        })
                    })
                })
            }
        )

        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.AgentMessage)
        assertEquals("Hello, world!", event.message)
        assertEquals("thr-structured", event.threadId)
        assertEquals("turn-structured", event.turnId)
    }
    
    @Test
    fun testParsePlanUpdate() {
        val notification = JsonRpcNotification(
            method = "codex/event/plan_update",
            params = buildJsonObject {
                put("threadId", "test-123")
                put("msg", buildJsonObject {
                    put("explanation", "Working on feature")
                    put("turnId", "turn-99")
                    put("plan", buildJsonArray {
                        add(buildJsonObject {
                            put("step", "Create file")
                            put("status", "in_progress")
                        })
                        add(buildJsonObject {
                            put("step", "Write tests")
                            put("status", "pending")
                        })
                    })
                })
            }
        )
        
        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.PlanUpdate)
        assertEquals("Working on feature", event.explanation)
        assertEquals(2, event.plan.size)
        assertEquals("Create file", event.plan[0].step)
        assertEquals("in_progress", event.plan[0].status)
        assertEquals("turn-99", event.turnId)
    }
    
    @Test
    fun testParseExecCommand() {
        val notification = JsonRpcNotification(
            method = "codex/event/exec_command_begin",
            params = buildJsonObject {
                put("threadId", "test-123")
                put("msg", buildJsonObject {
                    put("callId", "call-1")
                    put("command", buildJsonArray {
                        add("git")
                        add("status")
                    })
                    put("cwd", "/project")
                })
            }
        )
        
        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.ExecCommandBegin)
        assertEquals("call-1", event.callId)
        assertEquals(listOf("git", "status"), event.command)
        assertEquals("/project", event.cwd)
    }

    @Test
    fun testParseExecCommandEndWithDurationObject() {
        val notification = JsonRpcNotification(
            method = "codex/event/exec_command_end",
            params = buildJsonObject {
                put("threadId", "test-123")
                put("msg", buildJsonObject {
                    put("callId", "call-1")
                    put("exitCode", 0)
                    put("duration", buildJsonObject {
                        put("millis", 2500)
                    })
                })
            }
        )

        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.ExecCommandEnd)
        assertEquals(0, event.exitCode)
        assertEquals(2500L, event.duration)
    }

    @Test
    fun testParseExecCommandEndWithDurationParts() {
        val notification = JsonRpcNotification(
            method = "codex/event/exec_command_end",
            params = buildJsonObject {
                put("threadId", "test-123")
                put("msg", buildJsonObject {
                    put("callId", "call-2")
                    put("exitCode", 1)
                    put("duration", buildJsonObject {
                        put("seconds", 1)
                        put("nanos", 500_000_000)
                    })
                })
            }
        )

        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.ExecCommandEnd)
        assertEquals(1, event.exitCode)
        assertEquals(1_500L, event.duration)
    }
    
    @Test
    fun testParseTokenCount() {
        val notification = JsonRpcNotification(
            method = "codex/event/token_count",
            params = buildJsonObject {
                put("threadId", "test-123")
                put("msg", buildJsonObject {
                    put("turnId", "turn-5")
                    put("info", buildJsonObject {
                        put("totalTokenUsage", 1500)
                        put("lastTokenUsage", 300)
                    })
                })
            }
        )
        
        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.TokenCount)
        assertEquals(1500, event.totalTokenUsage)
        assertEquals(300, event.lastTokenUsage)
        assertEquals("turn-5", event.turnId)
    }
    
    @Test
    fun testParseUnknownEvent() {
        val notification = JsonRpcNotification(
            method = "codex/event/future_feature",
            params = buildJsonObject {
                put("threadId", "test-123")
            }
        )
        
        val event = parseCodexEvent(notification)
        assertTrue(event is CodexEvent.Unknown)
        assertEquals("codex/event/future_feature", event.method)
    }
}
