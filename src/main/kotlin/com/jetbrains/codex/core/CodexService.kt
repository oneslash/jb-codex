package com.jetbrains.codex.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.jetbrains.codex.protocol.JsonRpcClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

@Service(Service.Level.PROJECT)
class CodexService(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var processManager: ProcessManager? = null
    private var rpcClient: JsonRpcClient? = null
    private var lastWriter: java.io.BufferedWriter? = null

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val state: StateFlow<ServiceState> = _state

    val sessionRegistry = SessionRegistry()
    val approvalCache = ApprovalCache()
    
    suspend fun start(codexPath: String? = null, extraArgs: List<String> = emptyList()) {
        if (_state.value != ServiceState.Stopped) return

        _state.value = ServiceState.Starting

        try {
            val manager = ProcessManager(
                workingDir = project.basePath ?: System.getProperty("user.dir"),
                codexPath = codexPath,
                extraArgs = extraArgs
            )
            processManager = manager

            // Monitor process state for automatic reconnection
            scope.launch {
                manager.state.collect { processState ->
                    when (processState) {
                        is ProcessState.Running -> {
                            val writer = manager.stdinWriter
                            if (lastWriter !== writer) {
                                lastWriter = writer
                                try {
                                    initializeClient(manager)
                                } catch (ex: Exception) {
                                    lastWriter = null
                                    throw ex
                                }
                            }
                        }
                        is ProcessState.Failed -> {
                            _state.value = ServiceState.Error(processState.error)
                            rpcClient = null
                            lastWriter = null
                        }
                        is ProcessState.Restarting, ProcessState.Starting -> {
                            if (_state.value !is ServiceState.Starting) {
                                _state.value = ServiceState.Starting
                            }
                            rpcClient = null
                            lastWriter = null
                        }
                        is ProcessState.Stopped -> {
                            if (_state.value !is ServiceState.Stopped) {
                                _state.value = ServiceState.Stopped
                            }
                            rpcClient = null
                            lastWriter = null
                        }
                        else -> {}
                    }
                }
            }

            manager.start()

            when (val result = state.filter { it is ServiceState.Running || it is ServiceState.Error }.first()) {
                is ServiceState.Error -> throw IllegalStateException(result.message)
                else -> Unit
            }
        } catch (e: Exception) {
            _state.value = ServiceState.Error(e.message ?: "Unknown error")
            throw e
        }
    }

    private suspend fun initializeClient(manager: ProcessManager) {
        val client = JsonRpcClient(
            writer = manager.stdinWriter,
            outputFlow = manager.stdoutFlow,
            scope = scope
        )
        rpcClient = client

        client.initialize()

        _state.value = ServiceState.Running(client)
    }
    
    fun stop() {
        scope.launch {
            // Clean up all sessions
            sessionRegistry.shutdown()
        }

        // Clear approval cache
        approvalCache.clear()

        processManager?.stop()
        processManager = null
        rpcClient = null
        lastWriter = null
        _state.value = ServiceState.Stopped
    }

    fun getClient(): JsonRpcClient? {
        return rpcClient
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }
}

sealed class ServiceState {
    object Stopped : ServiceState()
    object Starting : ServiceState()
    data class Running(val client: JsonRpcClient) : ServiceState()
    data class Error(val message: String) : ServiceState()
}






