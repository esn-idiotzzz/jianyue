package com.joe.jmlite

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.*
import org.junit.jupiter.api.Test

class ReaderFullscreenTest {
    private fun activity(fullscreen:Boolean):Pair<ReaderActivity,WindowInsetsControllerCompat> {
        val activity=spyk(mockk<ReaderActivity>(relaxed=true),recordPrivateCalls=true)
        val controller=mockk<WindowInsetsControllerCompat>(relaxed=true)
        val preferences=mockk<ReaderPreferences>()
        every{preferences.fullscreen.get()}returns fullscreen
        every{activity.viewModel}returns mockk<ReaderViewModel>(relaxed=true)
        ReaderActivity::class.java.getDeclaredField("readerPreferences").apply{isAccessible=true}.set(activity,preferences)
        ReaderActivity::class.java.getDeclaredField("windowInsetsController\$delegate").apply{isAccessible=true}.set(activity,lazyOf(controller))
        return activity to controller
    }
    private fun menu(activity:ReaderActivity,visible:Boolean) {
        ReaderActivity::class.java.getDeclaredMethod("setMenuVisibility",Boolean::class.javaPrimitiveType)
            .apply{isAccessible=true}.invoke(activity,visible)
    }
    @Test fun `reading hides status and navigation bars together`() {
        val (activity,controller)=activity(true)
        menu(activity,false)
        verify{controller.hide(WindowInsetsCompat.Type.systemBars())}
        verify(exactly=0){controller.show(any())}
    }
    @Test fun `opening reader controls restores system bars`() {
        val (activity,controller)=activity(true)
        menu(activity,true)
        verify{controller.show(WindowInsetsCompat.Type.systemBars())}
        verify(exactly=0){controller.hide(any())}
    }
    @Test fun `explicit non fullscreen preference is respected`() {
        val (activity,controller)=activity(false)
        menu(activity,false)
        verify(exactly=0){controller.hide(any())}
    }
}
