package eu.kanade.presentation.webview

import android.content.pm.ApplicationInfo
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.joe.jmlite.TranslateAssets
import com.joe.jmlite.TranslateBridge
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
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
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen

private enum class TranslateMode(val js: String, val label: String) {
    OFF("off", "关闭"),
    ZH("zh", "转中文"),
    EN("en", "转外文"),
    BOTH("both", "双显"),
}
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
    val preferences = remember { context.getSharedPreferences("source_browser", Context.MODE_PRIVATE) }
    var adBlockEnabled by remember { mutableStateOf(preferences.getBoolean("adblock_enabled", true)) }
    val blockingEnabled = remember { AtomicBoolean(adBlockEnabled) }
    var showUrl by remember { mutableStateOf(false) }
    var showAdBlockSettings by remember { mutableStateOf(false) }
    var showAdBlockCode by remember { mutableStateOf(false) }
    var adBlockCode by remember { mutableStateOf("") }
    var adBlockCodeError by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var translateOpen by remember { mutableStateOf(false) }
    var translateMode by remember { mutableStateOf(TranslateMode.OFF) }
    var translateInjected by remember { mutableStateOf(false) }
    var translateBusy by remember { mutableStateOf(false) }
    var translateAuto by remember { mutableStateOf(false) }
    var translateSelect by remember { mutableStateOf(false) }
    var translateHighlight by remember { mutableStateOf(preferences.getBoolean("translate_highlight", true)) }
    var translateEpoch by remember { mutableStateOf(0) }
    var translateWebView by remember { mutableStateOf<WebView?>(null) }
    val translateScope = rememberCoroutineScope()

    fun resetTranslation() {
        translateEpoch++
        translateMode = TranslateMode.OFF
        translateAuto = false
        translateSelect = false
        translateInjected = false
        translateBusy = false
        translateOpen = false
    }
    fun ensureTranslate(view: WebView?) {
        if (view == null || translateInjected || translateBusy) return
        translateBusy = true
        val epoch = translateEpoch
        val highlight = translateHighlight
        translateScope.launch {
            try {
                view.addJavascriptInterface(TranslateBridge(context, view), "JYTranslate")
                val chunks = withContext(Dispatchers.IO) { TranslateAssets.chunks(context, highlight) }
                for (chunk in chunks) {
                    if (epoch != translateEpoch) return@launch
                    view.evaluateJavascript(chunk, null)
                }
                if (epoch == translateEpoch) translateInjected = true
            } catch (_: Exception) {
                Toast.makeText(context, "翻译组件加载失败", Toast.LENGTH_SHORT).show()
            } finally {
                if (epoch == translateEpoch) translateBusy = false
            }
        }
    }
    fun applyTranslate(mode: TranslateMode) {
        translateMode = mode
        if (mode == TranslateMode.OFF) {
            translateAuto = false
            translateSelect = false
        }
        translateWebView?.evaluateJavascript("window.__jyApply&&window.__jyApply('${mode.js}')", null)
    }
    fun setTranslateAuto(on: Boolean) {
        translateAuto = on
        if (on) translateSelect = false
        translateWebView?.evaluateJavascript("window.__jyWhenReady&&window.__jyWhenReady(function(){window.__jySetAuto($on)})", null)
    }
    fun setTranslateSelect(on: Boolean) {
        translateSelect = on
        if (on) translateAuto = false
        translateWebView?.evaluateJavascript("window.__jyWhenReady&&window.__jyWhenReady(function(){window.__jySetSelect($on)})", null)
    }
    fun setTranslateHighlight(on: Boolean) {
        translateHighlight = on
        preferences.edit().putBoolean("translate_highlight", on).apply()
        translateWebView?.evaluateJavascript("window.__jyWhenReady&&window.__jyWhenReady(function(){window.__jySetHighlight($on)})", null)
    }
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
                if (!isActive || !blockingEnabled.get()) return
                val css = blocker.css(url ?: return)
                view.evaluateJavascript(
                    "(function(){var s=document.getElementById('jianyue-adblock');" +
                        "if(!s){s=document.createElement('style');s.id='jianyue-adblock';document.documentElement.appendChild(s);}" +
                        "s.textContent=${JSONObject.quote(css)};})()",
                    null,
                )
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (blockingEnabled.get() && request != null && blocker.blocks(
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
                // Every page entry starts with translation off; the sheet is re-opened explicitly.
                resetTranslation()
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
                if (url != null && url != currentUrl) {
                    translateMode = TranslateMode.OFF
                    translateAuto = false
                    translateSelect = false
                    translateOpen = false
                    view.evaluateJavascript("window.__jyApply&&window.__jyApply('off')", null)
                }
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

    fun setAdBlockEnabled(enabled: Boolean) {
        blockingEnabled.set(enabled)
        adBlockEnabled = enabled
        preferences.edit().putBoolean("adblock_enabled", enabled).apply()
        windowStack.items.forEach { window ->
            window.webView?.evaluateJavascript("document.getElementById('jianyue-adblock')?.remove()", null)
            window.navigator.reload()
        }
    }

    if (showUrl) {
        AlertDialog(
            onDismissRequest = { showUrl = false },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text("网址信息") },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(currentUrl) } },
            confirmButton = { TextButton(onClick = { showUrl = false }) { Text("完成") } },
            dismissButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("图源网址", currentUrl))
                    showUrl = false
                }) { Text("复制网址") }
            },
        )
    }
    if (showAdBlockSettings) {
        AlertDialog(
            onDismissRequest = { showAdBlockSettings = false },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text("去广告拓展") },
            text = {
                Column {
                    Text("自用去广告规则 · 其他.prop")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (adBlockEnabled) "已开启" else "已关闭")
                        Switch(checked = adBlockEnabled, onCheckedChange = { enabled ->
                            if (enabled) setAdBlockEnabled(true) else {
                                showAdBlockSettings = false
                                showAdBlockCode = true
                                adBlockCode = ""
                                adBlockCodeError = false
                            }
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAdBlockSettings = false }) { Text("完成") } },
        )
    }
    if (showAdBlockCode) {
        fun closeCode() { showAdBlockCode = false; showAdBlockSettings = true; adBlockCodeError = false }
        AlertDialog(
            onDismissRequest = { closeCode() },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            title = { Text("输入恶臭数字关闭去广告插件") },
            text = {
                Column {
                    Text("关闭后将重新加载网页，网站广告可能重新显示。")
                    OutlinedTextField(
                        value = adBlockCode,
                        onValueChange = {
                            adBlockCode = it.filter { c -> c.isDigit() }.take(6)
                            adBlockCodeError = false
                        },
                        singleLine = true,
                        isError = adBlockCodeError,
                        label = { Text("恶臭数字") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    if (adBlockCodeError) {
                        Text(
                            text = "数字不正确",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (adBlockCode == "114514") {
                        setAdBlockEnabled(false)
                        showAdBlockCode = false
                        showAdBlockSettings = true
                        adBlockCodeError = false
                    } else {
                        adBlockCodeError = true
                    }
                }) { Text("确认关闭") }
            },
            dismissButton = { TextButton(onClick = { closeCode() }) { Text("取消") } },
        )
    }

    if (translateOpen) {
        val ready = translateInjected && !translateBusy
        AlertDialog(
            onDismissRequest = { translateOpen = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("译", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text("网页翻译")
                        Text(
                            text = "网页中英双显互译 · 按需开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "进入新页面时翻译默认关闭。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (translateBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
                        Text("正在准备翻译组件…", style = MaterialTheme.typography.bodySmall)
                    } else if (!translateInjected) {
                        Text(
                            text = "翻译组件未就绪，请关闭后重试。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("翻译方向", fontWeight = FontWeight.SemiBold)
                    TranslateMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = ready) { applyTranslate(mode) }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = translateMode == mode, onClick = null, enabled = ready)
                            Text(mode.label, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("高亮译文")
                        Switch(checked = translateHighlight, onCheckedChange = { setTranslateHighlight(it) }, enabled = ready)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("自动翻译")
                        Switch(checked = translateAuto, onCheckedChange = { setTranslateAuto(it) }, enabled = ready)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("选词翻译")
                        Switch(checked = translateSelect, onCheckedChange = { setTranslateSelect(it) }, enabled = ready)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            onClick = {
                                translateOpen = false
                                translateWebView?.evaluateJavascript("window.__jyWhenReady&&window.__jyWhenReady(function(){window.__jyClick('switchAPI')})", null)
                            },
                            enabled = ready,
                        ) {
                            Text("引擎设置")
                        }
                        TextButton(onClick = { applyTranslate(TranslateMode.OFF) }, enabled = ready) {
                            Text("还原原文")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { translateOpen = false }) { Text("完成") } },
        )
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
                            TranslateAction {
                                translateOpen = true
                                ensureTranslate(translateWebView)
                            }
                            AppBarActions(
                                listOf(
                                    AppBar.OverflowAction(
                                        title = "网址信息",
                                        onClick = { showUrl = true },
                                    ),
                                    AppBar.OverflowAction(
                                        title = "去广告拓展",
                                        onClick = { showAdBlockSettings = true },
                                    ),
                                ),
                            )
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
                    translateWebView = webView
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

@Composable
private fun TranslateAction(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "网页翻译" },
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "译",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}