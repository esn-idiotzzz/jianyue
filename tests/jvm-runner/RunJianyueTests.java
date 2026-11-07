import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

@SuppressWarnings("classfile")
public class RunJianyueTests {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(
                        DiscoverySelectors.selectClass("com.joe.jmlite.OnlineSearchTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.FeatureLogicTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.SourceBrowserTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.SourceLoginTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.BrowserAdBlockTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.WebViewLifecycleTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.ReaderFullscreenTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.UpdateReleaseTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.HistoryDetailsTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.CoverRequestsTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.CoverDiskCacheTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.CoverBindingTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.DownloadHeaderTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.FailedSourcesTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.ShelfOrderTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.BookSearchTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.DownloadHistoryTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.DownloadRefreshTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.DownloadTransitionTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.OnlineDownloadCompletionTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.DownloadPrivacyTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.FirstRunGuideTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.FolderPlacementTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.LibrarySnapshotTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.OnlineResumeHintTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.PredictiveBackTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.MainNavigationTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.PrivacyShelfTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.PrivacyIntroductionTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.PrivacyRecoveryTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.FolderCoverIndexTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.ShelfFavoriteScopeTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.SystemFolderNamesTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.SourceSearchTriggerTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.AppUpdateChecksTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.AppUpdateLifecycleTest"),
                        DiscoverySelectors.selectClass("com.joe.jmlite.AdaptiveLayoutTest"),
                        DiscoverySelectors.selectClass("eu.kanade.tachiyomi.data.download.DownloadProviderTest"),
                        DiscoverySelectors.selectClass("mihon.core.migration.MigratorTest"))
                .build();
        var listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        var output = new PrintWriter(System.out, true);
        summary.printTo(output);
        summary.printFailuresTo(output);
        if (summary.getTestsFoundCount() != 337 || summary.getTestsSucceededCount() != 337 ||
                summary.getTestsFailedCount() != 0 || summary.getTestsAbortedCount() != 0) {
            System.exit(1);
        }
    }
}
