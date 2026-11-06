package com.joe.jmlite

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.view.ViewOutlineProvider
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PredictiveBackTest {
    @AfterEach fun cleanup() = unmockkAll()

    private class Surface {
        val outline = mockk<ViewOutlineProvider>()
        val view = mockk<View>(relaxed = true)
        var scaleX = .91f
        var scaleY = 1.03f
        var translationX = 11f
        var translationY = -7f
        var alpha = .84f
        var clipped = false
        var provider: ViewOutlineProvider? = outline

        init {
            every { view.scaleX } answers { scaleX }
            every { view.scaleY } answers { scaleY }
            every { view.translationX } answers { translationX }
            every { view.translationY } answers { translationY }
            every { view.alpha } answers { alpha }
            every { view.clipToOutline } answers { clipped }
            every { view.outlineProvider } answers { provider }
            every { view.width } returns 1000
            every { view.height } returns 1600
            every { view.setScaleX(any()) } answers { scaleX = firstArg() }
            every { view.setScaleY(any()) } answers { scaleY = firstArg() }
            every { view.setTranslationX(any()) } answers { translationX = firstArg() }
            every { view.setTranslationY(any()) } answers { translationY = firstArg() }
            every { view.setAlpha(any()) } answers { alpha = firstArg() }
            every { view.setClipToOutline(any()) } answers { clipped = firstArg() }
            every { view.setOutlineProvider(any()) } answers { provider = firstArg() }
        }

        fun assertRestored() {
            assertEquals(.91f, scaleX)
            assertEquals(1.03f, scaleY)
            assertEquals(11f, translationX)
            assertEquals(-7f, translationY)
            assertEquals(.84f, alpha)
            assertFalse(clipped)
            assertSame(outline, provider)
        }
    }

    private class Fixture {
        val surface = Surface()
        val enabled = AtomicBoolean(true)
        val actions = AtomicInteger()
        val activity = mockk<Activity>(relaxed = true)
        val owner = PredictiveBack(activity, surface.view, { enabled.get() }, { actions.incrementAndGet() })
        val callback: OnBackAnimationCallback

        init {
            val api = PredictiveBack::class.java.declaredClasses.single { it.simpleName == "Api34" }
            val method = api.getDeclaredMethod("callback", PredictiveBack::class.java).apply { isAccessible = true }
            callback = method.invoke(null, owner) as OnBackAnimationCallback
        }

        fun start(progress: Float = .6f) {
            callback.onBackStarted(event(0f))
            callback.onBackProgressed(event(progress))
        }

        private fun event(progress: Float) = mockk<BackEvent> {
            every { swipeEdge } returns BackEvent.EDGE_LEFT
            every { getProgress() } returns progress
        }
    }

    private class Animation {
        val animator = mockk<ValueAnimator>(relaxed = true)
        val ends = mutableListOf<Animator.AnimatorListener>()
        val updates = mutableListOf<ValueAnimator.AnimatorUpdateListener>()

        init {
            mockkStatic(ValueAnimator::class)
            every { ValueAnimator.ofFloat(any<Float>(), any<Float>()) } returns animator
            every { animator.addListener(capture(ends)) } just Runs
            every { animator.addUpdateListener(capture(updates)) } just Runs
            every { animator.animatedValue } returns .95f
        }

        fun finish() = ends.last().onAnimationEnd(animator)
    }

    @Test fun `cancel restores the original view geometry and outline without navigating`() {
        val fixture = Fixture()
        val animation = Animation()
        fixture.start()
        assertTrue(fixture.surface.scaleX < .91f)
        assertNotSame(fixture.surface.outline, fixture.surface.provider)
        fixture.callback.onBackCancelled()
        animation.finish()
        fixture.surface.assertRestored()
        assertEquals(0, fixture.actions.get())
    }

    @Test fun `close during a gesture restores geometry and unregisters only once`() {
        val fixture = Fixture()
        val unregister = mockk<Runnable>(relaxed = true)
        PredictiveBack::class.java.getDeclaredField("unregister").apply { isAccessible = true }.set(fixture.owner, unregister)
        fixture.start()
        fixture.owner.close()
        fixture.owner.close()
        fixture.surface.assertRestored()
        verify(exactly = 1) { unregister.run() }
        assertEquals(0, fixture.actions.get())
    }

    @Test fun `callbacks after close cannot transform the surface or invoke an action`() {
        val fixture = Fixture()
        fixture.owner.close()
        fixture.start()
        fixture.callback.onBackCancelled()
        fixture.callback.onBackInvoked()
        fixture.surface.assertRestored()
        verify(exactly = 0) { fixture.surface.view.setScaleX(any()) }
        assertEquals(0, fixture.actions.get())
    }

    @Test fun `ordinary back invokes the action once without starting a gesture`() {
        val fixture = Fixture()
        fixture.callback.onBackInvoked()
        assertEquals(1, fixture.actions.get())
        fixture.surface.assertRestored()
        verify(exactly = 0) { fixture.surface.view.setScaleX(any()) }
    }

    @Test fun `a changed navigation target cancels a gesture before invocation`() {
        val fixture = Fixture()
        fixture.start()
        fixture.enabled.set(false)
        fixture.callback.onBackInvoked()
        fixture.surface.assertRestored()
        assertEquals(0, fixture.actions.get())
    }

    @Test fun `a changed navigation target during settling cannot run the old action`() {
        val fixture = Fixture()
        val animation = Animation()
        fixture.start()
        fixture.callback.onBackInvoked()
        fixture.enabled.set(false)
        animation.finish()
        fixture.surface.assertRestored()
        assertEquals(0, fixture.actions.get())
    }

    @Test fun `closing a settling gesture ignores stale animation callbacks`() {
        val fixture = Fixture()
        val animation = Animation()
        fixture.start()
        fixture.callback.onBackInvoked()
        fixture.owner.close()
        animation.updates.last().onAnimationUpdate(animation.animator)
        animation.finish()
        fixture.surface.assertRestored()
        verify(exactly = 1) { animation.animator.removeAllListeners() }
        verify(exactly = 1) { animation.animator.cancel() }
        assertEquals(0, fixture.actions.get())
    }
}
