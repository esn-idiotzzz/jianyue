package eu.kanade.tachiyomi.ui.webview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.toWebViewHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class WebViewViewModel(
    @Assisted val sourceId: Long?,
    private val sourceManager: SourceManager,
    private val network: NetworkHelper,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long?): WebViewViewModel
    }

    /** Null until the source it belongs to has been resolved. */
    val headers: StateFlow<Map<String, String>?>
        field = MutableStateFlow<Map<String, String>?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val source = sourceId?.let { sourceManager.get(it) as? HttpSource }
            headers.value = try {
                source?.headers?.toWebViewHeaders().orEmpty()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to build headers" }
                emptyMap()
            }
        }
    }

    fun defaultUserAgentProvider() = network.defaultUserAgentProvider()
}
