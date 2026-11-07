# Direct JVM regression runner

Place these four files in the source package's `tests/jvm-runner/` directory:
`RunJianyueTests.java`, `run-tests.ps1`, `export-test-classpath.gradle`, and this README.
Use the project's JDK 21 and Android SDK configuration. From the source root, export the actual
AGP unit-test runtime and run the tests directly through JUnit Platform:

On Windows, use an ASCII-only checkout path for this Java launcher; this machine could not
read the generated classpath correctly under a Chinese checkout path.

```powershell
Push-Location .\mihon
.\gradlew.bat :app:exportJianyueTestClasspath --init-script ..\tests\jvm-runner\export-test-classpath.gradle '-Dorg.gradle.isolated-projects=false' --no-configuration-cache --console=plain -Pinclude-telemetry=false -Penable-updater=false
if ($LASTEXITCODE -ne 0) { throw 'Test classpath export failed.' }
Pop-Location
.\tests\jvm-runner\run-tests.ps1 -JavaHome $env:JAVA_HOME
```

The export task compiles its declared test-runtime inputs without executing Gradle test workers.
Disable isolated projects for this init task, as shown above; this project's default enables them.
The runner uses `mihon/app/build/jianyue-test-runtime.args`; it does not depend on a previous
Gradle worker's temporary file or on this build machine's absolute paths. The exported argument
file is local build output and should be regenerated after moving the project or changing dependencies.

The runner selects the compiled production project's tests:

- `OnlineSearchTest`: 9 tests for timeout isolation, source failures, streaming results, source-aware
  deduplication, cancellation, source defaults, and bounded 16-source concurrency.
- `DownloadProviderTest`: 16 parameterized cases for existing downloaded-chapter naming compatibility.
- `MigratorTest`: 6 migration-strategy cases.

- `UpdateReleaseTest`: 13 cases for numeric version ordering, release notes, stable releases,
  HTTP errors, malformed responses, project URL validation, and uploaded APK availability.
- `HistoryDetailsTest`: 8 cases for loading/empty history states and local JM download button state.
- `CoverRequestsTest`: 7 cases for parallel loading, shared downloads, cancellation, queue bounds,
  visible-request priority, and retries.
- `ShelfOrderTest`: 12 cases for insertion, contiguous ordering, hover zones, cancellation plans,
  restored order, and the two-book merge plan.

3.5.0 also selects `BookSearchTest` (7), `DownloadHistoryTest` (11), `DownloadRefreshTest` (3),
`DownloadTransitionTest` (7), `OnlineDownloadCompletionTest` (6), and `DownloadPrivacyTest` (4).
These cover cross-folder search, durable records and deletion markers, completion refresh,
the two-button transition geometry, committed web-download side effects, and hidden titles.
JSON state tests use the test-only JSON-java dependency; it is not included in the app.

3.6.0 adds `FirstRunGuideTest` (7), `FolderPlacementTest` (14), `LibrarySnapshotTest` (2),
`OnlineResumeHintTest` (6), and `PredictiveBackTest` (7). These verify explicit manual acknowledgment,
online-folder restrictions without partial writes, reused download snapshots, saved chapter hints,
and gesture cancellation, lifecycle cleanup, and stale navigation guards.

3.6.1 adds `SourceSearchTriggerTest` (7), `AppUpdateChecksTest` (9), and `AppUpdateLifecycleTest` (4).
`PredictiveBackTest` now has 14 cases, including immediate navigation at the gesture position,
duplicate-commit prevention, temporary layer cleanup, and per-dialog window registration.

It exits unsuccessfully unless all 239 cases pass. Output is saved beside the script as `results.log`.
These JVM tests do not prove Android UI, real extension loading, website availability, or device reading.

The direct runner works around this machine's Gradle test-worker startup failure (`ClassNotFoundException: Test`).
The precise worker command-line cause has not been established. The classpath-export task and this
runner were both executed successfully for the previous 3.1.0 source: 53 tests passed, 0 failed.
Re-run against the current source to verify all 239 cases, including the 3.6.1 regressions.
The exported classpath came from the actual AGP `testDebugUnitTest` task inputs.

FeatureLogicTest adds 12 cases for ranking, confirmed-empty versus network errors/timeouts, and source popular browsing.

SourceBrowserTest adds 12 cases for selected-source resolution, popular browsing, fallback/paging consistency, source search and clearing, and cancellation.

3.7.0 adds PrivacyShelfTest (13) for isolated membership, system-directory mapping, recursive folder transfers, classification rejection, independent sort persistence, and shared favorite state without relocation. Update lifecycle tests now verify one check on opening and host attachment without polling.

3.7.1 adds PrivacyIntroductionTest (4) for the shared first-use decision across pull and move entry points, delayed actions and cancellation; SystemFolderNamesTest (4) for protected identities in both shelves, rejected writes and ordinary-folder renaming. These do not replace device touch/rendering checks.

PrivacyRecoveryTest adds 6 cases for exact-code validation without unintended writes, clearing the PIN, preserving the first-use decision, and entry after recovery/session reset.

FolderCoverIndexTest adds 5 cases for cached folder membership, visible-cover invalidation, count changes, immutable membership and cross-shelf snapshot isolation.

3.8.0 adds MainNavigationTest (5 cases) for four root tabs moving the task to the background and search closing first, plus two PredictiveBackTest cases for disabled root previews and child/root state changes. The runner now requires 246 successful tests. Actual Android desktop and IME gestures still require device verification.

The source login window adds BrowserAdBlockTest (12), SourceLoginTest (5), and two SourceBrowserTest regressions. The runner now requires 265 successful cases. These cover bundled-rule matching, exceptions, resource/domain scope, keeping main navigation accessible, explicit authentication errors, and preserving login failures during catalogue fallback. Browser rendering, confirmation dialogs, device back gestures and real account login still require device verification.

3.8.1 adds WebViewLifecycleTest (3) for detachment, stopping requests, destruction and idempotent disposal, plus ReaderFullscreenTest (3) for hiding both system bars during reading, restoring them with menus and honoring the fullscreen preference. The runner now requires 271 successful cases. These JVM checks do not establish device memory usage, OEM gesture behavior or long-running stability.

4.0.0 adds CoverDiskCacheTest (7) for persistence beyond decoded bitmap eviction, source isolation, hashed filenames, bounded eviction, invalidation, unavailable storage and rejected writes. OnlineSearchTest now checks failure identities for exceptions/timeouts, repository order despite completion order, and exclusion of successful empty sources. The direct runner requires 279 successful tests. These checks do not replace device scrolling, keyboard focus, login or shared-list rendering checks.

DownloadInputFocusTest is a separate Android instrumentation test. It alternates real touches between the download page editors three times and checks the selected focus after keyboard layout plus typed text isolation. It must be run on a connected Android device; it is not part of the 279 JVM tests.

4.1.0 extends the direct runner to 285 successful JVM tests. Its device tests also check cover-view reuse across 100 bindings and input focus after repeated keyboard hiding, tapping and deferred header focus requests.

- `CoverBindingTest`: 3 cases for preserving a visible local cover, clearing recycled images, and retrying missing covers.
- `DownloadHeaderTest`: 3 cases for dispatching remembered input focus; Android focus propagation and real keyboard resize remain device tests.
