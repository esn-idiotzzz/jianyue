package com.joe.jmlite

import android.app.Activity
import android.content.SharedPreferences
import io.mockk.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FirstRunGuideTest {
    private class Fixture {
        val activity=mockk<Activity>(relaxed=true)
        val preferences=mockk<SharedPreferences>()
        val editor=mockk<SharedPreferences.Editor>()
        val callbacks=AtomicInteger()
        var persisted=false
        init {
            every { activity.isFinishing } returns false
            every { activity.isDestroyed } returns false
            every { preferences.getBoolean(FirstRunGuide.ACKNOWLEDGED,false) } answers { persisted }
            every { preferences.edit() } returns editor
            every { editor.putBoolean(FirstRunGuide.ACKNOWLEDGED,true) } returns editor
            every { editor.apply() } answers { persisted=true }
        }
        fun guide()=FirstRunGuide(activity,preferences) { callbacks.incrementAndGet() }
        fun ready(guide:FirstRunGuide){
            FirstRunGuide::class.java.getDeclaredField("confirmReady").apply{isAccessible=true}.setBoolean(guide,true)
        }
    }

    @Test fun `first use requires the guide without marking it as read`() {
        val fixture=Fixture();val guide=fixture.guide()
        assertTrue(guide.needsAcknowledgment())
        assertFalse(guide.isAcknowledged)
        assertFalse(guide.isShowing)
        verify(exactly=0) { fixture.preferences.edit() }
        assertEquals(0,fixture.callbacks.get())
    }

    @Test fun `a previous acknowledgment skips the guide on a new launch`() {
        val fixture=Fixture();fixture.persisted=true
        assertFalse(fixture.guide().needsAcknowledgment())
        assertTrue(fixture.guide().isAcknowledged)
        verify(exactly=0) { fixture.preferences.edit() }
    }

    @Test fun `the explicit acknowledgment persists once and releases the launch slot`() {
        val fixture=Fixture();val guide=fixture.guide()
        guide.acknowledge()
        assertFalse(fixture.persisted)
        assertEquals(0,fixture.callbacks.get())
        fixture.ready(guide)
        guide.acknowledge();guide.acknowledge()
        assertTrue(fixture.persisted)
        assertFalse(guide.needsAcknowledgment())
        assertFalse(fixture.guide().needsAcknowledgment())
        verify(exactly=1) { fixture.editor.putBoolean(FirstRunGuide.ACKNOWLEDGED,true) }
        verify(exactly=1) { fixture.editor.apply() }
        assertEquals(1,fixture.callbacks.get())
    }

    @Test fun `activity recreation before confirmation requires the guide again`() {
        val fixture=Fixture();fixture.guide().close()
        assertFalse(fixture.persisted)
        assertTrue(fixture.guide().needsAcknowledgment())
        verify(exactly=0) { fixture.preferences.edit() }
        assertEquals(0,fixture.callbacks.get())
    }

    @Test fun `closing a destroyed screen cannot acknowledge or start an update popup`() {
        val fixture=Fixture();val guide=fixture.guide()
        guide.close();guide.acknowledge()
        assertFalse(guide.needsAcknowledgment())
        assertFalse(fixture.persisted)
        verify(exactly=0) { fixture.preferences.edit() }
        assertEquals(0,fixture.callbacks.get())
    }

    @Test fun `a finishing activity does not display or acknowledge a new guide`() {
        val fixture=Fixture();every { fixture.activity.isFinishing } returns true
        val guide=fixture.guide()
        assertFalse(guide.showIfNeeded())
        guide.acknowledge()
        verify(exactly=0) { fixture.preferences.edit() }
    }

    @Test fun `a destroyed activity leaves the manual unconfirmed for the next instance`() {
        val fixture=Fixture();every { fixture.activity.isDestroyed } returns true
        val guide=fixture.guide()
        assertFalse(guide.showIfNeeded())
        guide.acknowledge()
        assertFalse(fixture.persisted)
        every { fixture.activity.isDestroyed } returns false
        assertTrue(fixture.guide().needsAcknowledgment())
        assertEquals(0,fixture.callbacks.get())
    }
}
