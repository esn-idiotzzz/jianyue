package eu.kanade.tachiyomi.extension

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource

/** Loads the sources bundled with JianYue; extensions are updated with the app. */
@Inject
@SingleIn(AppScope::class)
class ExtensionManager(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: SourcePreferences,
    private val trustExtension: TrustExtension,
) {
    private val initialized = CompletableDeferred<Unit>()
    private val loadMutex = Mutex()
    private val loadedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Loaded>())
    val loadedExtensionsFlow = loadedExtensionMapFlow
        .onStart { initialized.await() }
        .map { it.values.toList() }

    private val notLoadedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.NotLoaded>())

    init {
        scope.launch(Dispatchers.IO) {
            loadExtensions()
            merge(
                trustExtension.changes(),
                preferences.enabledContentWarnings.changes().distinctUntilChanged().drop(1).map {},
                preferences.applyContentWarningsToInstalled.changes().distinctUntilChanged().drop(1).map {},
            ).collectLatest { loadExtensions() }
        }
    }

    suspend fun getLoadedExtensions(): List<Extension.Loaded> {
        initialized.await()
        return loadedExtensionMapFlow.value.values.toList()
    }

    suspend fun getNotLoadedExtensions(): List<Extension.NotLoaded> {
        initialized.await()
        return notLoadedExtensionMapFlow.value.values.toList()
    }

    suspend fun getExtensionPackage(sourceId: Long): String? =
        getLoadedExtensions().find { extension -> extension.sources.any { it.id == sourceId } }?.pkgName

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> = loadedExtensionsFlow.map { extensions ->
        extensions.find { extension -> extension.sources.any { it.id == sourceId } }?.pkgName
    }

    fun getSourceData(id: Long): StubSource? = loadedExtensionMapFlow.value.values
        .asSequence()
        .flatMap { it.sources.asSequence() }
        .find { it.id == id }
        ?.let(StubSource::from)

    suspend fun reloadBundledExtensions() {
        loadExtensions()
    }

    private suspend fun loadExtensions() = loadMutex.withLock {
        try {
            val extensions = ExtensionLoader.loadExtensions(context, loadedExtensionMapFlow.value)
            loadedExtensionMapFlow.value = extensions.filterIsInstance<Extension.Loaded>().associateBy { it.pkgName }
            notLoadedExtensionMapFlow.value = extensions.filterIsInstance<Extension.NotLoaded>().associateBy { it.pkgName }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load bundled sources" }
        } finally {
            initialized.complete(Unit)
        }
    }
}
