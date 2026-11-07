package com.joe.jmlite

import android.view.ViewGroup
import android.webkit.WebView
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebViewNavigator
import eu.kanade.presentation.webview.WebViewWindow
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebViewLifecycleTest {
    @Test fun `closing a window detaches stops and destroys its browser`() {
        val parent=mockk<ViewGroup>(relaxed=true)
        val view=mockk<WebView>(relaxed=true)
        every{view.parent}returns parent
        val window=WebViewWindow(WebContent.NavigatorOnly,mockk<WebViewNavigator>()).apply{webView=view}
        window.destroy()
        verifyOrder{parent.removeView(view);view.stopLoading();view.destroy()}
        assertNull(window.webView)
    }
    @Test fun `repeated disposal does not reuse or destroy an already released browser`() {
        val view=mockk<WebView>(relaxed=true)
        val window=WebViewWindow(WebContent.NavigatorOnly,mockk<WebViewNavigator>()).apply{webView=view}
        window.destroy();window.destroy()
        verify(exactly=1){view.stopLoading()}
        verify(exactly=1){view.destroy()}
        assertNull(window.webView)
    }
    @Test fun `closing an unopened popup is safe`() {
        val window=WebViewWindow(WebContent.NavigatorOnly,mockk<WebViewNavigator>())
        window.destroy()
        assertNull(window.webView)
    }
}
