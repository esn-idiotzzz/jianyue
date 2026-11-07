package eu.kanade.presentation.webview

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import com.joe.jmlite.BrowserAdBlock
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import org.json.JSONObject
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen

class WebViewWindow(webContent: WebContent, val navigator: WebViewNavigator) {
    var state by mutableStateOf(WebViewState(webContent))
    var popupMessage: Message? = null
        private set
    var webView: WebView? = null

    fun destroy() {
        val view = webView ?: return
        webView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    constructor(popupMessage: Message, navigator: WebViewNavigator) : this(WebContent.NavigatorOnly, navigator) {
        this.popupMessage = popupMessage
    }
}

@Composable
fun WebViewScreenContent(
    onNavigateUp: () -> Unit,
    initialTitle: String?,
    url: String,
    defaultUserAgentProvider: () -> String,
    headers: Map<String, String> = emptyMap(),
    onUrlChange: (String) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val adBlock by produceState<BrowserAdBlock?>(null) {
        value = withContext(Dispatchers.IO) {
            BrowserAdBlock(context.assets.open("browser-adblock.txt").bufferedReader().use { it.readText() })
        }
    }
    if (adBlock == null) {
        LoadingScreen()
        return
    }
    val blocker = adBlock!!
    val coroutineScope = rememberCoroutineScope()
    val windowStack = remember {
        mutableStateStackOf(
            WebViewWindow(
                WebContent.Url(url = url, additionalHttpHeaders = headers),
                WebViewNavigator(coroutineScope),
            ),
        )
    }

    val currentWindow = windowStack.lastItemOrNull!!
    val navigator = currentWindow.navigator

    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(url) }
    var showCloudflareHelp by remember { mutableStateOf(false) }
    var isActive by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            isActive = false
            CookieManager.getInstance().flush()
            windowStack.items.forEach { it.destroy() }
        }
    }

    val headersHost = remember { url.toUri().host }

    val webClient = remember {
        object : AccompanistWebViewClient() {
            private fun hideAds(view: WebView, url: String?) {
                if (!isActive) return
                val css = blocker.css(url ?: return)
                view.evaluateJavascript(
                    "(function(){var s=document.getElementById('jianyue-adblock');" +
                        "if(!s){s=document.createElement('style');s.id='jianyue-adblock';document.documentElement.appendChild(s);}" +
                        "s.textContent=${JSONObject.quote(css)};})()",
                    null,
                )
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null && blocker.blocks(
                    request.url.toString(),
                    request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: currentUrl,
                    BrowserAdBlock.resourceType(request.url.toString(), request.requestHeaders),
                    request.isForMainFrame,
                )) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                hideAds(view, url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!isActive) return
                super.onPageFinished(view, url)
                hideAds(view, url)
                CookieManager.getInstance().flush()
                scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = "window._cf_chl_opt" in html || "Ray ID is" in html
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Ignore intents urls
                if (url.startsWith("intent://")) return true

                // Only send source headers to the source itself
                if (request.url.host != headersHost) return false

                // Only open valid web urls
                if (url.startsWith("http") || url.startsWith("https")) {
                    if (url != view?.url) {
                        view?.loadUrl(url, headers)
                        return true
                    }
                }

                return false
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Not handling it kills the app along with the page
                logcat(LogPriority.ERROR) {
                    "WebView renderer gone: crashed=${detail.didCrash()}, " +
                        "priority=${detail.rendererPriorityAtExit()}, url=$currentUrl"
                }
                view.context.toast(MR.strings.information_webview_crashed, Toast.LENGTH_LONG)
                onNavigateUp()
                return true
            }
        }
    }

    val webChromeClient = remember {
        object : AccompanistWebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // if it wasn't initiated by a user gesture, we should ignore it like a normal browser would
                if (isUserGesture) {
                    windowStack.push(WebViewWindow(resultMsg, WebViewNavigator(coroutineScope)))
                    return true
                }
                return false
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) {
                    result.confirm()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) {
                    result.cancel()
                    return true
                }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(
                view: WebView,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                if (!isActive) {
                    result.cancel()
                    return true
                }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }
    }

    fun initializePopup(webView: WebView, message: Message): WebView {
        val transport = message.obj as WebView.WebViewTransport
        transport.webView = webView
        message.sendToTarget()
        return webView
    }

    BackHandler {
        val initialUri = url.toUri()
        val currentUri = currentUrl.toUri()
        val atHome = currentUri.host == initialUri.host &&
            currentUri.path.orEmpty().trimEnd('/') == initialUri.path.orEmpty().trimEnd('/')
        when {
            atHome -> onNavigateUp()
            navigator.canGoBack -> navigator.navigateBack()
            windowStack.size > 1 -> windowStack.pop()
            else -> onNavigateUp()
        }
    }

    Scaffold(
        topBar = {
            Box {
                Column {
                    AppBar(
                        title = currentWindow.state.pageTitle ?: initialTitle,
                        navigateUp = onNavigateUp,
                        navigationIcon = MaterialSymbols.Rounded.Close,
                        actions = {
                            // Refreshes the page that is currently open; it never jumps back home.
                            IconButton(onClick = { navigator.reload() }) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Refresh,
                                    contentDescription = "刷新网页",
                                )
                            }
                        },
                    )

                    if (showCloudflareHelp) {
                        Surface(
                            modifier = Modifier.padding(8.dp),
                        ) {
                            WarningBanner(
                                textRes = MR.strings.information_cloudflare_help,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        uriHandler.openUri(
                                            "https://mihon.app/docs/guides/troubleshooting/#cloudflare",
                                        )
                                    },
                            )
                        }
                    }
                }
                when (val loadingState = currentWindow.state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> LinearProgressIndicator(
                        progress = { loadingState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        // We need to key the WebView composable to the window object since simply updating the WebView composable will
        // not cause it to re-invoke the WebView factory and render the new current window's WebView. This lets us
        // completely reset the WebView composable when the current window switches.
        key(currentWindow) {
            WebView(
                state = currentWindow.state,
                captureBackPresses = false,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                navigator = navigator,
                onCreated = { webView ->
                    webView.setDefaultSettings()
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    // Debug mode (chrome://inspect/#devices)
                    if (BuildConfig.DEBUG &&
                        0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
                    ) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    webView.setUserAgent(headers["user-agent"] ?: defaultUserAgentProvider())
                },
                onDispose = { webView ->
                    val window = windowStack.items.find { it.webView == webView }
                    if (window == null) {
                        // If we couldn't find any window on the stack that owns this WebView, it means that we can
                        // safely dispose of it because the window containing it has been closed.
                        currentWindow.destroy()
                    } else {
                        // The composable is being disposed but the WebView object is not.
                        // When the WebView element is recomposed, we will want the WebView to resume from its state
                        // before it was unmounted, we won't want it to reset back to its original target.
                        window.state.content = WebContent.NavigatorOnly
                    }
                },
                client = webClient,
                chromeClient = webChromeClient,
                factory = { context ->
                    currentWindow.webView
                        ?: WebView(context).also { webView ->
                            currentWindow.webView = webView
                            currentWindow.popupMessage?.let {
                                initializePopup(webView, it)
                            }
                        }
                },
            )
        }
    }
}

