package com.mushrea.code.device.call

/**
 * Explicit state machine for an agent-managed phone call (spec section 23). Every transition is
 * total and validated here, so the service and the MCP surface can never report a call that is,
 * say, SPEAKING after COMPLETED. Terminal states accept no further events except RESET.
 */
object CallStateMachine {

    enum class State {
        IDLE,
        DIALING,
        RINGING,
        CONNECTED,
        LISTENING,
        THINKING,
        SPEAKING,
        COMPLETED,
        FAILED,
        BUSY,
        NO_ANSWER,
        DECLINED,
        DISCONNECTED,
        STOPPED,
    }

    enum class Event {
        DIAL,
        RING,
        CONNECT,
        START_LISTENING,
        START_THINKING,
        START_SPEAKING,
        REMOTE_HUNG_UP,
        FINISH_NORMALLY,
        FAIL,
        REMOTE_BUSY,
        NO_ANSWER_TIMEOUT,
        REMOTE_DECLINED,
        STOP,
        RESET,
    }

    val terminalStates: Set<State> =
        setOf(State.COMPLETED, State.FAILED, State.BUSY, State.NO_ANSWER, State.DECLINED, State.DISCONNECTED, State.STOPPED)

    private val transitions: Map<Pair<State, Event>, State> =
        buildMap {
            put(State.IDLE to Event.DIAL, State.DIALING)
            put(State.DIALING to Event.RING, State.RINGING)
            put(State.DIALING to Event.FAIL, State.FAILED)
            put(State.DIALING to Event.STOP, State.STOPPED)
            put(State.RINGING to Event.CONNECT, State.CONNECTED)
            put(State.RINGING to Event.REMOTE_BUSY, State.BUSY)
            put(State.RINGING to Event.NO_ANSWER_TIMEOUT, State.NO_ANSWER)
            put(State.RINGING to Event.REMOTE_DECLINED, State.DECLINED)
            put(State.RINGING to Event.FAIL, State.FAILED)
            put(State.RINGING to Event.STOP, State.STOPPED)
            put(State.CONNECTED to Event.START_LISTENING, State.LISTENING)
            put(State.CONNECTED to Event.REMOTE_HUNG_UP, State.DISCONNECTED)
            put(State.CONNECTED to Event.STOP, State.STOPPED)
            put(State.LISTENING to Event.START_THINKING, State.THINKING)
            put(State.LISTENING to Event.REMOTE_HUNG_UP, State.DISCONNECTED)
            put(State.LISTENING to Event.STOP, State.STOPPED)
            put(State.THINKING to Event.START_SPEAKING, State.SPEAKING)
            put(State.THINKING to Event.START_LISTENING, State.LISTENING)
            put(State.THINKING to Event.REMOTE_HUNG_UP, State.DISCONNECTED)
            put(State.THINKING to Event.STOP, State.STOPPED)
            put(State.SPEAKING to Event.START_LISTENING, State.LISTENING)
            put(State.SPEAKING to Event.REMOTE_HUNG_UP, State.DISCONNECTED)
            put(State.SPEAKING to Event.STOP, State.STOPPED)
            terminalStates.forEach { state ->
                put(state to Event.RESET, State.IDLE)
            }
        }

    /** The state after [event], or null when the transition is illegal (callers keep state). */
    fun reduce(
        current: State,
        event: Event,
    ): State? = transitions[current to event]

    fun isTerminal(state: State): Boolean = state in terminalStates
}
