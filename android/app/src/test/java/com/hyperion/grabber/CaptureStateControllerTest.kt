package com.hyperion.grabber

import com.hyperion.grabber.CaptureStateController.Action
import com.hyperion.grabber.CaptureStateController.Event
import com.hyperion.grabber.CaptureStateController.State
import com.hyperion.grabber.CaptureStateController.transition
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStateControllerTest {

    @Test
    fun userPauseFromRunningPauses() {
        val r = transition(State(), Event.UserPause)
        assertEquals(Action.Pause, r.action)
        assertEquals(State(paused = true), r.state)
    }

    @Test
    fun userPauseWhenAlreadyPausedIsNoOp() {
        val r = transition(State(paused = true), Event.UserPause)
        assertEquals(Action.None, r.action)
        assertEquals(State(paused = true), r.state)
    }

    @Test
    fun userResumeFromPausedResumes() {
        val r = transition(State(paused = true), Event.UserResume)
        assertEquals(Action.Resume, r.action)
        assertEquals(State(), r.state)
    }

    @Test
    fun userResumeWhenRunningIsNoOp() {
        val r = transition(State(), Event.UserResume)
        assertEquals(Action.None, r.action)
        assertEquals(State(), r.state)
    }

    @Test
    fun screenOffFromRunningPausesAndDisconnects() {
        val r = transition(State(), Event.ScreenOff)
        assertEquals(Action.PauseAndDisconnect, r.action)
        assertEquals(State(paused = true, autoPausedByScreen = true), r.state)
    }

    @Test
    fun screenOffIgnoredIfAlreadyUserPaused() {
        // Critical: user opted to pause; a stray SCREEN_OFF must not stomp that
        // intent (otherwise SCREEN_ON would auto-resume against the user's wish).
        val s = State(paused = true, autoPausedByScreen = false)
        val r = transition(s, Event.ScreenOff)
        assertEquals(Action.None, r.action)
        assertEquals(s, r.state)
    }

    @Test
    fun screenOnAfterScreenOffResumes() {
        // Regression: the v1.6 → v1.7 wake-from-standby path must resume.
        val s = State(paused = true, autoPausedByScreen = true)
        val r = transition(s, Event.ScreenOn)
        assertEquals(Action.Resume, r.action)
        assertEquals(State(), r.state)
    }

    @Test
    fun screenOnAfterUserPauseDoesNotResume() {
        // The whole point of autoPausedByScreen: if the user paused, we must
        // not silently un-pause them when the TV wakes.
        val s = State(paused = true, autoPausedByScreen = false)
        val r = transition(s, Event.ScreenOn)
        assertEquals(Action.None, r.action)
        assertEquals(s, r.state)
    }

    @Test
    fun userPauseClearsAutoPausedFlag() {
        // If the screen turned off and then the user explicitly hit Pause,
        // ownership of "paused" transfers to them. A later SCREEN_ON must
        // NOT auto-resume.
        val afterScreenOff = transition(State(), Event.ScreenOff).state
        val afterUserPause = transition(afterScreenOff, Event.UserPause).state
        assertEquals(State(paused = true, autoPausedByScreen = false), afterUserPause)

        val r = transition(afterUserPause, Event.ScreenOn)
        assertEquals(Action.None, r.action)
    }

    @Test
    fun userResumeWhileAutoPausedClearsFlag() {
        // User wakes from auto-pause manually before the screen comes back on.
        // The next SCREEN_OFF/ON cycle should behave fresh.
        val s = State(paused = true, autoPausedByScreen = true)
        val afterResume = transition(s, Event.UserResume).state
        assertEquals(State(), afterResume)
    }

    @Test
    fun fullScreenOffOnRoundTripReturnsToRunningState() {
        var s = State()
        s = transition(s, Event.ScreenOff).state
        s = transition(s, Event.ScreenOn).state
        assertEquals(State(), s)
    }
}
