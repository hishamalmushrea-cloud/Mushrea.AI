package com.mushrea.code.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mushrea.code.core.runtime.RuntimeWorkTracker
import com.mushrea.code.runtime.local.LocalRuntimeCommandRunner
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TerminalLineType { INPUT, OUTPUT, ERROR, SYSTEM }

data class TerminalLine(
    val text: String,
    val type: TerminalLineType,
)

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val isRunning: Boolean = false,
    val currentInput: String = "",
    val workingDirectory: String = "/root",
)

class TerminalViewModel(
    private val commandRunner: LocalRuntimeCommandRunner,
    /**
     * A shell command run here is real work on the runtime's proot process just like a chat turn,
     * but the terminal never touches
     * [com.mushrea.code.data.repository.RuntimeActivityRepository] - the only place that
     * already tracks that as work - so without a lease the device could suspend mid-command.
     */
    private val runtimeWork: RuntimeWorkTracker,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            TerminalUiState(
                lines =
                    listOf(
                        TerminalLine("OpenCode Terminal - PRoot Alpine Linux", TerminalLineType.SYSTEM),
                    ),
            ),
        )
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private val history = ArrayDeque<String>()
    private var historyIndex: Int? = null
    private var runningJob: Job? = null
    private val runningProcess = AtomicReference<Process?>(null)

    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        recordHistory(trimmed)

        _state.update { s ->
            s.copy(
                lines = appendLine(s.lines, TerminalLine("${s.workingDirectory} $ $trimmed", TerminalLineType.INPUT)),
                currentInput = "",
                isRunning = true,
            )
        }

        runningJob =
            viewModelScope.launch {
                val result =
                    runtimeWork.withLease(TERMINAL_LEASE_TAG) {
                        withContext(Dispatchers.IO) {
                            val fullCommand =
                                if (_state.value.workingDirectory != "/root") {
                                    "cd ${_state.value.workingDirectory} && $trimmed"
                                } else {
                                    trimmed
                                }
                            commandRunner.runShell(
                                fullCommand,
                                timeoutSeconds = COMMAND_TIMEOUT_SECONDS,
                                processListener = { process -> runningProcess.set(process) },
                            )
                        }
                    }
                runningProcess.set(null)

                _state.update { s ->
                    val newLines = s.lines.toMutableList()
                    if (result.output.isNotBlank()) {
                        result.output.lines().forEach { line ->
                            val type = if (result.exitCode != 0) TerminalLineType.ERROR else TerminalLineType.OUTPUT
                            newLines.add(TerminalLine(line, type))
                        }
                    }
                    if (result.exitCode != 0 && result.output.isBlank()) {
                        newLines.add(TerminalLine("exit code: ${result.exitCode}", TerminalLineType.ERROR))
                    }
                    s.copy(
                        lines = trimScrollback(newLines),
                        isRunning = false,
                        workingDirectory = resolveWorkingDirectory(s.workingDirectory, trimmed),
                    )
                }
            }
    }

    /** Hard-stops the running command: destroys the proot child, then cancels the waiter. */
    fun stop() {
        val process = runningProcess.getAndSet(null)
        process?.destroyForcibly()
        val job = runningJob
        if (process == null && (job == null || !job.isActive)) return
        job?.cancel()
        _state.update { s ->
            if (!s.isRunning) {
                s
            } else {
                s.copy(lines = appendLine(s.lines, TerminalLine("^C stopped", TerminalLineType.SYSTEM)), isRunning = false)
            }
        }
    }

    /** Steps back through the commands run this session (like pressing ArrowUp). */
    fun historyUp() {
        if (history.isEmpty()) return
        val next =
            when (val index = historyIndex) {
                null -> history.lastIndex
                else -> (index - 1).coerceAtLeast(0)
            }
        historyIndex = next
        _state.update { it.copy(currentInput = history[next]) }
    }

    /** Steps forward through the history; past the newest entry clears the input. */
    fun historyDown() {
        val index = historyIndex ?: return
        val next = index + 1
        if (next >= history.size) {
            historyIndex = null
            _state.update { it.copy(currentInput = "") }
        } else {
            historyIndex = next
            _state.update { it.copy(currentInput = history[next]) }
        }
    }

    fun updateInput(text: String) {
        historyIndex = null
        _state.update { it.copy(currentInput = text) }
    }

    fun clear() {
        _state.update {
            it.copy(
                lines =
                    listOf(
                        TerminalLine("OpenCode Terminal - PRoot Alpine Linux", TerminalLineType.SYSTEM),
                    ),
            )
        }
    }

    private fun recordHistory(command: String) {
        if (history.lastOrNull() == command) return
        history.addLast(command)
        while (history.size > MAX_HISTORY) history.removeFirst()
        historyIndex = null
    }

    private fun resolveWorkingDirectory(
        current: String,
        command: String,
    ): String {
        val cdPattern = Regex("""^cd\s+(.*)$""")
        val match = cdPattern.find(command.trim()) ?: return current
        val target = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
        return when {
            target.startsWith("/") -> target
            target == "~" -> "/root"
            target == ".." -> current.substringBeforeLast("/", "").ifEmpty { "/" }.ifEmpty { "/" }
            target == "." -> current
            else -> if (current == "/") "/$target" else "$current/$target"
        }
    }

    private fun appendLine(
        lines: List<TerminalLine>,
        line: TerminalLine,
    ): List<TerminalLine> {
        return trimScrollback(lines + line)
    }

    private fun trimScrollback(lines: List<TerminalLine>): List<TerminalLine> {
        return if (lines.size > MAX_SCROLLBACK) lines.takeLast(MAX_SCROLLBACK) else lines
    }

    private companion object {
        const val MAX_SCROLLBACK = 500
        const val MAX_HISTORY = 50
        const val TERMINAL_LEASE_TAG = "terminal"
        const val COMMAND_TIMEOUT_SECONDS = 120L
    }
}
