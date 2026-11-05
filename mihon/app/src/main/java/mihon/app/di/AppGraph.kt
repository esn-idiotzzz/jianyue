package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadWorker
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.CrashLogUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.metro.IsDebugBuild
import mihon.data.cache.ChapterCache
import mihon.domain.extension.repository.ExtensionStoreRepository
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences

@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class],
)
interface AppGraph : ViewModelGraph {
    fun inject(app: App)
    fun inject(readerActivity: ReaderActivity)
    fun inject(webViewActivity: WebViewActivity)
    fun inject(downloadWorker: DownloadWorker)
    fun inject(notificationReceiver: NotificationReceiver)
    fun inject(notificationReceiver: SecureActivityDelegateImpl)

    val context: Context

    val viewModelFactory: MetroViewModelFactory

    val basePreferences: BasePreferences
    val uiPreferences: UiPreferences
    val readerPreferences: ReaderPreferences
    val networkPreferences: NetworkPreferences
    val libraryPreferences: LibraryPreferences
    val sourcePreferences: SourcePreferences
    val storagePreferences: StoragePreferences
    val privacyPreferences: PrivacyPreferences
    val securityPreferences: SecurityPreferences
    val downloadPreferences: DownloadPreferences

    val crashLogUtil: CrashLogUtil

    val downloadManager: DownloadManager

    val trustExtension: TrustExtension
    val extensionStoreRepository: ExtensionStoreRepository

    val sourceManager: SourceManager
    val extensionManager: ExtensionManager
    val chapterCache: ChapterCache
    val coverCache: CoverCache
    val downloadCache: DownloadCache

    val json: Json
    val protoBuf: ProtoBuf
    val xml: XML
    val networkHelper: NetworkHelper
    val javaScriptEngine: JavaScriptEngine

    val getFavorites: GetFavorites
    // JM Lite bridge uses the same reader database and lifecycle as Mihon.
    val jmMangaRepository: tachiyomi.domain.manga.repository.MangaRepository
    val jmChapterRepository: tachiyomi.domain.chapter.repository.ChapterRepository
    val getCategories: GetCategories
    val resetViewerFlags: ResetViewerFlags
    val resetCategoryFlags: ResetCategoryFlags

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}
