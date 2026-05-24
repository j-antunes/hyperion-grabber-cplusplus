package com.hyperion.grabber

// Pure state machine for the screen-grabber's pause/resume decisions.
// Extracted from ScreenGrabberService so the transitions can be unit-tested
// without bringing the whole Android Service / MediaProjection runtime.
//
// Invariants the tests pin down:
//   - User-initiated pause survives a screen-off → screen-on cycle.
//   - Screen-off auto-pause is silently ignored if we're already paused.
//   - Auto-pause closes the TCP socket so resume reconnects fresh (the 8s
//     reconnect lag we hit on v1.6 wake-from-standby).
object CaptureStateController {

    data class State(
        val paused: Boolean = false,
        val autoPausedByScreen: Boolean = false,
    )

    enum class Event { UserPause, UserResume, ScreenOff, ScreenOn }

    enum class Action {
        None,
        Pause,                 // stop capture, keep TCP connection
        PauseAndDisconnect,    // stop capture, drop TCP so resume reconnects
        Resume,
    }

    data class Transition(val state: State, val action: Action)

    fun transition(state: State, event: Event): Transition = when (event) {
        Event.UserPause ->
            if (state.paused) Transition(state.copy(autoPausedByScreen = false), Action.None)
            else Transition(State(paused = true, autoPausedByScreen = false), Action.Pause)

        Event.UserResume ->
            if (state.paused) Transition(State(paused = false, autoPausedByScreen = false), Action.Resume)
            else Transition(state.copy(autoPausedByScreen = false), Action.None)

        Event.ScreenOff ->
            if (state.paused) Transition(state, Action.None)
            else Transition(State(paused = true, autoPausedByScreen = true), Action.PauseAndDisconnect)

        Event.ScreenOn ->
            if (state.autoPausedByScreen) Transition(State(paused = false, autoPausedByScreen = false), Action.Resume)
            else Transition(state, Action.None)
    }
}
