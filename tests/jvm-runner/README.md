# Direct JVM regression runner

Place these four files in the source package's `tests/jvm-runner/` directory:
`RunJianyueTests.java`, `run-tests.ps1`, `export-test-classpath.gradle`, and this README.
Use the project's JDK 21 and Android SDK configuration. From the source root, export the actual
AGP unit-test runtime and run the tests directly through JUnit Platform:

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

- `OnlineSearchTest`: 7 tests for timeout isolation, source failures, streaming results, source-aware
  deduplication, cancellation, and source defaults.
- `DownloadProviderTest`: 16 parameterized cases for existing downloaded-chapter naming compatibility.
- `MigratorTest`: 6 migration-strategy cases.

- `UpdateReleaseTest`: 13 cases for numeric version ordering, release notes, stable releases,
  HTTP errors, malformed responses, project URL validation, and uploaded APK availability.

It exits unsuccessfully unless all 66 cases pass. Output is saved beside the script as `results.log`.
These JVM tests do not prove Android UI, real extension loading, website availability, or device reading.

The direct runner works around this machine's Gradle test-worker startup failure (`ClassNotFoundException: Test`).
The precise worker command-line cause has not been established. The classpath-export task and this
runner were both executed successfully for the previous 3.1.0 source: 53 tests passed, 0 failed.
Re-run against the current source to verify all 66 cases, including update detection.
The exported classpath came from the actual AGP `testDebugUnitTest` task inputs.

FeatureLogicTest adds 12 cases for ranking, confirmed-empty versus network errors/timeouts, and source popular browsing.

SourceBrowserTest adds 12 cases for selected-source resolution, popular browsing, fallback/paging consistency, source search and clearing, and cancellation.
