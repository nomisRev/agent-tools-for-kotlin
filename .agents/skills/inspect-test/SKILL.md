---
name: inspect-test
description: Inspect Gradle/Kotlin test results and failing tests using the self-contained inspect-test.init.gradle.kts init script, without needing the dev-tools plugin applied to the target project. Use whenever a Gradle test task has run (or failed) and you need to see which tests failed and their stack traces.
---

# Inspect Test

Read failing tests straight from Gradle's JUnit XML reports
(`build/test-results/**/TEST-*.xml`) via the `inspectTest` task, applied through the
self-contained init script [`inspect-test.init.gradle.kts`](inspect-test.init.gradle.kts).
No plugin resolution, network access, or version pinning is required, and no target project
build file needs to be modified.

Always pass `-q`/`--quiet` to `./gradlew` — the task and the wired test listener are designed
to produce clean, token-efficient output only under quiet mode.

## Setup

This skill bundles a self-contained copy of `inspect-test.init.gradle.kts` (see
[inspect-test.init.gradle.kts](inspect-test.init.gradle.kts)). Point `--init-script` at its
absolute path from the target repository — no plugin resolution, network access, or copying
into the target project is required:

```bash
./gradlew --init-script /absolute/path/to/.agents/skills/inspect-test/inspect-test.init.gradle.kts -q inspectTest
```

The init script registers `inspectTest` on the root project and every subproject, and wires a
failing-test summary onto every `Test` task in the build. Target a specific module with a task
path, e.g. `:app:inspectTest`.

## Workflow

1. Run the relevant test task first (a test task must have produced `build/test-results` XML
   reports before `inspectTest` has anything to read):

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/inspect-test/inspect-test.init.gradle.kts -q :app:test
   ```

   With `-q`, a green run still prints a short summary (e.g. `Ran 91 tests successfully in
   :test. 1 test ignored.`). A failing run prints the failing test names plus a hint to re-run
   `inspectTest`:

   ```
   Failed tests in :test:
     - suite_io.github.nomisrev.articles.ArticleRouteSuite > Boom!

   Run ./gradlew -q inspectTest --name "<test name>" to see the stack trace.
   ```

2. List every failing test across all modules (omit `--name`):

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/inspect-test/inspect-test.init.gradle.kts -q inspectTest
   ```

3. Show the stack trace for a specific failure by matching a case-insensitive substring of the
   test name or full name:

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/inspect-test/inspect-test.init.gradle.kts -q inspectTest --name "should parse"
   ```

4. Narrow to a single Gradle module when names collide across modules:

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/inspect-test/inspect-test.init.gradle.kts -q inspectTest --name "should parse" --module app
   ```

5. Control how much of the stack trace is shown (default is 10 lines; `0` shows the full trace):

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/inspect-test/inspect-test.init.gradle.kts -q inspectTest --name "should parse" --lines 0
   ```

## Options

- `--name <text>` — case-insensitive substring matched against the test name and full name.
  Omit to list every failing test.
- `--module <name>` — only match failures from this Gradle module.
- `--lines <n>` — max stack-trace lines per failure; `0` shows the full trace (default `10`).

## Notes

- If `inspectTest` reports no failing tests found, a test task hasn't produced reports yet — run one first (e.g. `./gradlew -q test`).
- If the target project already applies the `io.github.nomisrev.dev-tools` Gradle plugin (see
  the plugin's [README](https://github.com/nomisRev/agent-tools-for-kotlin#readme)), the
  `inspectTest` task is already available and the `--init-script` flag can be skipped.
- The plugin also provides a `jarSearch` task (via `jar-search.init.gradle.kts`) for searching
  dependency jars for packages, types, and members — unrelated to test inspection.
