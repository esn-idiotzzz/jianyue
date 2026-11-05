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
                        DiscoverySelectors.selectClass("com.joe.jmlite.UpdateReleaseTest"),
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
        if (summary.getTestsFoundCount() != 66 || summary.getTestsSucceededCount() != 66 ||
                summary.getTestsFailedCount() != 0 || summary.getTestsAbortedCount() != 0) {
            System.exit(1);
        }
    }
}
