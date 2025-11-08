package com.jetbrains.codex.core

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.min
import kotlin.math.pow

sealed class ProcessState {
    object Stopped : ProcessState()
    object Starting : ProcessState()
    object Running : ProcessState()
    data class Restarting(val attempt: Int, val delayMs: Long) : ProcessState()
    data class Failed(val error: String, val restartIn: Long?) : ProcessState()
}

class ProcessManager(
    private val workingDir: String,
    private val codexPath: String? = null,
    private val extraArgs: List<String> = emptyList(),
    private val maxRestartAttempts: Int = 5
) {
    private val log = logger<ProcessManager>()
    private var process: Process? = null
    private var readerJob: Job? = null
    private var monitorJob: Job? = null
    private var shouldRun = false
    private var restartAttempts = 0

    private val _stdoutFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val stdoutFlow: SharedFlow<String> = _stdoutFlow

    private val _state = MutableStateFlow<ProcessState>(ProcessState.Stopped)
    val state: StateFlow<ProcessState> = _state

    lateinit var stdinWriter: BufferedWriter
        private set
    
    fun start() {
        shouldRun = true
        restartAttempts = 0
        startProcess()
    }

    private fun startProcess() {
        try {
            _state.value = ProcessState.Starting

            val binary = codexPath ?: findCodexBinary()
                ?: throw IllegalStateException("codex binary not found in PATH")

            log.info("Starting codex app-server from: $binary ${if (extraArgs.isNotEmpty()) extraArgs.joinToString(" ", prefix = "(extra args: ", postfix = ")") else ""}")

            val command = mutableListOf(binary, "app-server").apply {
                addAll(extraArgs)
            }

            val pb = ProcessBuilder(command)
                .directory(java.io.File(workingDir))
                .redirectErrorStream(false)

            val proc = pb.start()
            process = proc

            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

            // Reset restart attempts on successful start
            restartAttempts = 0
            _state.value = ProcessState.Running

            // Start stdout reader
            readerJob = CoroutineScope(Dispatchers.IO).launch {
                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                try {
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        _stdoutFlow.emit(line)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        log.error("Error reading stdout", e)
                    }
                }
            }

            // Start stderr reader
            CoroutineScope(Dispatchers.IO).launch {
                val errReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
                try {
                    while (isActive) {
                        val line = errReader.readLine() ?: break
                        log.warn("codex stderr: $line")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Monitor process lifecycle
            monitorJob = CoroutineScope(Dispatchers.IO).launch {
                val exitCode = proc.waitFor()
                log.warn("Codex process exited with code: $exitCode")

                if (shouldRun && restartAttempts < maxRestartAttempts) {
                    handleCrash()
                } else if (restartAttempts >= maxRestartAttempts) {
                    _state.value = ProcessState.Failed(
                        "Max restart attempts ($maxRestartAttempts) reached",
                        null
                    )
                    log.error("Max restart attempts reached, giving up")
                }
            }

        } catch (e: Exception) {
            log.error("Failed to start codex process", e)
            _state.value = ProcessState.Failed(e.message ?: "Unknown error", null)

            if (shouldRun && restartAttempts < maxRestartAttempts) {
                handleCrash()
            }
        }
    }

    private fun handleCrash() {
        restartAttempts++
        val backoffMs = calculateBackoff(restartAttempts)

        log.warn("Scheduling restart attempt $restartAttempts in ${backoffMs}ms")
        _state.value = ProcessState.Restarting(restartAttempts, backoffMs)

        CoroutineScope(Dispatchers.IO).launch {
            delay(backoffMs)
            if (shouldRun) {
                log.info("Restarting codex process (attempt $restartAttempts)")
                startProcess()
            }
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        val baseMs = 1000L
        val exponentialMs = (baseMs * 2.0.pow(attempt - 1)).toLong()
        return min(exponentialMs, 30000L) // Cap at 30 seconds
    }
    
    fun stop() {
        shouldRun = false
        readerJob?.cancel()
        monitorJob?.cancel()
        process?.destroyForcibly()
        process = null
        _state.value = ProcessState.Stopped
        log.info("Codex process stopped")
    }
    
    private fun findCodexBinary(): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val paths = pathEnv.split(System.getProperty("path.separator"))
        
        for (dir in paths) {
            val candidate = java.io.File(dir, "codex")
            if (candidate.exists() && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }
        return null
    }
}







