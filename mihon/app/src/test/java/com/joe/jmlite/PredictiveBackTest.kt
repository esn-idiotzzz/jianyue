package com.joe.jmlite

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

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
        var layer = View.LAYER_TYPE_NONE
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
            every { view.layerType } answers { layer }
            every { view.isHardwareAccelerated } returns true
            every { view.setScaleX(any()) } answers { scaleX = firstArg() }
            every { view.setScaleY(any()) } answers { scaleY = firstArg() }
            every { view.setTranslationX(any()) } answers { translationX = firstArg() }
            every { view.setTranslationY(any()) } answers { translationY = firstArg() }
            every { view.setAlpha(any()) } answers { alpha = firstArg() }
            every { view.setClipToOutline(any()) } answers { clipped = firstArg() }
            every { view.setOutlineProvider(any()) } answers { provider = firstArg() }
            every { view.setLayerType(any(), any()) } answers { layer = firstArg() }
        }

        fun assertRestored() {
            assertEquals(.91f, scaleX)
            assertEquals(1.03f, scaleY)
            assertEquals(11f, translationX)
            assertEquals(-7f, translationY)
            assertEquals(.84f, alpha)
            assertFalse(clipped)
            assertSame(outline, provider)
            assertEquals(View.LAYER_TYPE_NONE, layer)
        }
    }

    private class Fixture(val preview: AtomicBoolean = AtomicBoolean(true)) {
        val surface = Surface()
        val enabled = AtomicBoolean(true)
        val actions = AtomicInteger()
        val exits = AtomicBoolean(false)
        val activity = mockk<Activity>(relaxed = true)
        val window = mockk<Window>(relaxed = true)
        var actionHook: (() -> Unit)? = null
        val owner: PredictiveBack
        val callback: OnBackAnimationCallback

        init {
            every { activity.window } returns window
            owner = PredictiveBack(activity, surface.view, { enabled.get() }, { exits.get() }, { preview.get() }, {
                actions.incrementAndGet()
                actionHook?.invoke()
            })
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

    @Test fun `root gesture has no preview and navigates only when committed`() {
        val fixture = Fixture(AtomicBoolean(false))
        fixture.exits.set(true)
        fixture.start()
        fixture.callback.onBackCancelled()
        assertEquals(0, fixture.actions.get())
        fixture.surface.assertRestored()
        fixture.start()
        fixture.callback.onBackInvoked()
        fixture.callback.onBackInvoked()
        assertEquals(1, fixture.actions.get())
        verify(exactly = 0) { fixture.surface.view.setScaleX(any()) }
        verify(exactly = 0) { fixture.surface.view.setLayerType(any(), any()) }
        fixture.surface.assertRestored()
    }

    @Test fun `same callback previews a child then stops previewing after returning to root`() {
        val fixture = Fixture(AtomicBoolean(false))
        val animation = Animation()
        fixture.preview.set(true)
        fixture.start()
        assertTrue(fixture.surface.scaleX < .91f)
        fixture.callback.onBackCancelled()
        animation.finish()
        fixture.surface.assertRestored()
        fixture.preview.set(false)
        clearMocks(fixture.surface.view, answers = false)
        fixture.start()
        fixture.callback.onBackCancelled()
        verify(exactly = 0) { fixture.surface.view.setScaleX(any()) }
        fixture.surface.assertRestored()
    }

    @Test fun `duplicate ordinary exit callbacks navigate once and stop rearms the owner`() {
        val fixture = Fixture()
        fixture.exits.set(true)
        fixture.callback.onBackInvoked()
        fixture.callback.onBackInvoked()
        assertEquals(1, fixture.actions.get())
        fixture.owner.onStop()
        fixture.callback.onBackInvoked()
        assertEquals(2, fixture.actions.get())
        fixture.surface.assertRestored()
    }

    @Test fun `a changed navigation target cancels a gesture before invocation`() {
        val fixture = Fixture()
        fixture.start()
        fixture.enabled.set(false)
        fixture.callback.onBackInvoked()
        fixture.surface.assertRestored()
        assertEquals(0, fixture.actions.get())
    }

    @Test fun `commit navigates immediately at the preview position then restores the new page`() {
        val fixture = Fixture()
        val animation = Animation()
        var scaleAtNavigation = 1f
        var shiftAtNavigation = 0f
        fixture.actionHook = {
            scaleAtNavigation = fixture.surface.scaleX
            shiftAtNavigation = fixture.surface.translationX
        }
        fixture.start()
        fixture.callback.onBackInvoked()
        assertEquals(1, fixture.actions.get())
        assertEquals(.91f * PredictiveBack.previewScale(.6f), scaleAtNavigation)
        assertEquals(11f + PredictiveBack.previewShift(1000, .6f, true), shiftAtNavigation)
        assertEquals(scaleAtNavigation, fixture.surface.scaleX)
        verify(exactly = 1) { animation.animator.start() }
        animation.finish()
        fixture.surface.assertRestored()
        assertEquals(1, fixture.actions.get())
    }

    @Test fun `duplicate invocation and progress after commit do not navigate twice`() {
        val fixture = Fixture()
        val animation = Animation()
        fixture.start()
        fixture.callback.onBackInvoked()
        val scale = fixture.surface.scaleX
        fixture.callback.onBackInvoked()
        fixture.callback.onBackStarted(mockk<BackEvent>(relaxed = true))
        fixture.callback.onBackProgressed(mockk<BackEvent>(relaxed = true))
        fixture.callback.onBackCancelled()
        assertEquals(scale, fixture.surface.scaleX)
        assertEquals(1, fixture.actions.get())
        verify(exactly = 1) { animation.animator.start() }
        animation.finish()
        fixture.surface.assertRestored()
    }

    @Test fun `a departing page holds the preview until stop then releases its hardware layer`() {
        val fixture = Fixture()
        val animation = Animation()
        fixture.exits.set(true)
        fixture.start()
        fixture.callback.onBackInvoked()
        fixture.callback.onBackInvoked()
        assertEquals(1, fixture.actions.get())
        assertTrue(fixture.surface.scaleX < .91f)
        assertEquals(View.LAYER_TYPE_HARDWARE, fixture.surface.layer)
        verify(exactly = 0) { animation.animator.start() }
        fixture.owner.onStop()
        fixture.surface.assertRestored()
    }

    @Test fun `an existing software layer is preserved instead of being replaced`() {
        val fixture = Fixture()
        fixture.surface.layer = View.LAYER_TYPE_SOFTWARE
        fixture.start()
        assertEquals(View.LAYER_TYPE_SOFTWARE, fixture.surface.layer)
        fixture.owner.onStop()
        assertEquals(View.LAYER_TYPE_SOFTWARE, fixture.surface.layer)
        verify(exactly = 0) { fixture.surface.view.setLayerType(View.LAYER_TYPE_HARDWARE, any()) }
    }

    @Test fun `a software rendered window does not allocate a hardware layer`() {
        val fixture = Fixture()
        every { fixture.surface.view.isHardwareAccelerated } returns false
        fixture.start()
        assertEquals(View.LAYER_TYPE_NONE, fixture.surface.layer)
        fixture.owner.onStop()
        fixture.surface.assertRestored()
        verify(exactly = 0) { fixture.surface.view.setLayerType(View.LAYER_TYPE_HARDWARE, any()) }
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
        assertEquals(1, fixture.actions.get())
    }

    @Test fun `a dialog callback registers with its own window and cancels that dialog`() {
        val activity = mockk<Activity>(relaxed = true)
        val activityWindow = mockk<Window>(relaxed = true)
        val dialogWindow = mockk<Window>(relaxed = true)
        val dispatcher = mockk<OnBackInvokedDispatcher>(relaxed = true)
        val callback = slot<OnBackInvokedCallback>()
        val dialog = mockk<AlertDialog>(relaxed = true)
        every { activity.window } returns activityWindow
        every { dialogWindow.onBackInvokedDispatcher } returns dispatcher
        every { dialog.isShowing } returns true
        every { dispatcher.registerOnBackInvokedCallback(any(), capture(callback)) } just Runs
        val constructor = PredictiveBack::class.java.getDeclaredConstructor(
            Activity::class.java, Window::class.java, View::class.java,
            BooleanSupplier::class.java, BooleanSupplier::class.java, Runnable::class.java,
        ).apply { isAccessible = true }
        val owner = constructor.newInstance(activity, dialogWindow, Surface().view,
            BooleanSupplier { dialog.isShowing }, BooleanSupplier { true }, Runnable { dialog.cancel() })
        val api = PredictiveBack::class.java.declaredClasses.single { it.simpleName == "Api33" }
        val register = api.getDeclaredMethod("register", PredictiveBack::class.java).apply { isAccessible = true }
        val unregister = register.invoke(null, owner) as Runnable
        callback.captured.onBackInvoked()
        verify(exactly = 1) { dialog.cancel() }
        verify(exactly = 0) { activityWindow.onBackInvokedDispatcher }
        unregister.run()
        verify(exactly = 1) { dispatcher.unregisterOnBackInvokedCallback(callback.captured) }
        owner.close()
    }

    @Test fun `a disabled owner cannot preview or navigate behind a blocking manual`() {
        val fixture = Fixture()
        fixture.enabled.set(false)
        fixture.start()
        fixture.callback.onBackInvoked()
        assertEquals(0, fixture.actions.get())
        fixture.surface.assertRestored()
        verify(exactly = 0) { fixture.surface.view.setScaleX(any()) }
        verify(exactly = 0) { fixture.surface.view.setLayerType(any(), any()) }
    }
}
