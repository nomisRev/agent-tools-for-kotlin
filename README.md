# Agent Tools For Kotlin

This repository aims to make coding agents more efficient with Kotlin/Gradle by fixing two common issues:

 - Searching dependency jars and their available types, functions, and members, imports, etc. See [JarSearchTask](src/main/kotlin/jarsearch/JarSearchTask.kt).
 - Inspecting tests results. See [InspectTestTask](src/main/kotlin/testreport/InspectTestTask.kt)

It also tries to make working with Gradle more token efficient and thus works great with Gradle's `quiet` mode.
The AGENTS.md below nudges the LLM/Agent to always specify `-q` flag to avoid Gradle's verbose output,
this way the agent can almost always easily read the entire Gradle output even when using a small `-tail`.

## Installation

The plugin is published to Maven Central under the `io.github.nomisrev` group as the
`io.github.nomisrev.dev-tools` Gradle plugin. Apply it to any Gradle module you want the
`jarSearch` and `inspectTest` tasks in.

### Using the `plugins {}` block (Kotlin DSL)

```kotlin
plugins {
  id("io.github.nomisrev.dev-tools") version "<version>"
}
```

### Using the version catalog (`gradle/libs.versions.toml`)

```toml
[plugins]
dev-tools = { id = "io.github.nomisrev.dev-tools", version = "<version>" }
```

```kotlin
plugins {
  alias(libs.plugins.dev.tools)
}
```

### Applying to every module of a multi-module build

```kotlin
// root build.gradle.kts
plugins {
  id("io.github.nomisrev.dev-tools") version "<version>" apply false
}

subprojects {
  apply(plugin = "io.github.nomisrev.dev-tools")
}
```

Make sure `mavenCentral()` is in the plugin/repository resolution, e.g. in `settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}
```

### Without touching a project's build files (`--init-script`)

If you don't want to (or can't) edit a project's build scripts, apply the tasks through a
Gradle [init script](https://docs.gradle.org/current/userguide/init_scripts.html). Two
fully self-contained scripts are provided — each inlines its task implementation, so they need
no plugin resolution, no network access, and no version to pin:

- [`jar-search.init.gradle.kts`](jar-search.init.gradle.kts) — registers `jarSearch`.
- [`inspect-test.init.gradle.kts`](inspect-test.init.gradle.kts) — registers `inspectTest` and
  wires the failing-test summary onto every `Test` task.

Copy the one(s) you want into the target repository (or point at them with an absolute path)
and pass them with `--init-script`:

```bash
./gradlew --init-script jar-search.init.gradle.kts -q jarSearch --dependency arrow-core
./gradlew --init-script jar-search.init.gradle.kts -q :app:jarSearch --kind type --query Either
./gradlew --init-script inspect-test.init.gradle.kts -q inspectTest --name "should parse"
```

You can pass `--init-script` more than once to enable both at the same time. The scripts
register the tasks on the root project and every subproject, so target a specific module with a
task path (e.g. `:app:jarSearch`). The inlined code is a copy of the sources under
`src/main/kotlin`; keep the two in sync when changing task behaviour.

When running `./gradlew -q test` the plugin will also output a small summary of the test results.
In case all tests pass:
```console
./gradlew -q build

Ran 91 tests successfully in :test. 1 test ignored.`
```
When tests fail:
```console
./gradlew -q build

Failed tests in :test:
  - suite_io.github.nomisrev.articles.ArticleRouteSuite > Boom!

Run ./gradlew -q inspectTest --name "<test name>" to see the stack trace.

93 tests completed, 1 failed, 1 skipped

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test'.
> There were failing tests. See the report at: file:///Users/simonvergauwen/Developer/ktor-arrow-example/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights from a Build Scan (powered by Develocity).

BUILD FAILED in 9s
```

## AGENTS.md

## Gradle

Always use `-q`/`--quiet` when running `./gradlew` commands to avoid noisy output.

Prefer `./gradlew -q :module:test` over `./gradlew -q build`

### Searching dependency jars

Use the `jarSearch` Gradle task to inspect packages, types, and members in  resolved dependency jars.

Run it with `-q` to keep output clean:

```bash
./gradlew -q jarSearch --dependency <spec> [options]
```

### `--dependency` (required)
One of:
- `*` or a configuration name — search every jar on that configuration
- a Gradle coordinate `group:artifact[:version]`
- a version-catalog alias (e.g. `arrow-core`)
- a direct `.jar` path

### Options
- `--query <text>` — package, type, function, or method to look for
- `--kind <all|package|type|function|top_level_function|method>` — default `all`
  (`function`, `top_level_function`, and `method` require `--query`)
- `--configuration <name>` — configuration to search/resolve against (default `compileClasspath`)
- `--limit <n>` — max results per section (default `20`)
- `--include-non-public` — include non-public members
- `--include-synthetic` — include synthetic/compiler-generated members
- `--raw-signatures` — print raw JVM descriptors
- `--transitive <auto|true|false>` — transitive search mode (default `auto`)

### Examples
```bash
# List packages/types in a catalog dependency
./gradlew -q jarSearch --dependency arrow-core

# Find a type
./gradlew -q jarSearch --dependency arrow-core --kind type --query Either

# Find methods matching a name
./gradlew -q jarSearch --dependency "io.arrow-kt:arrow-core" --kind method --query fold
```

### Inspecting failing tests

Use the `inspectTest` Gradle task to read failing tests straight from Gradle's
JUnit XML reports (the `TEST-*.xml` files under each module's
`build/test-results`). Run a test task first, then query the results; no
intermediate file or external tooling is required.

Run it with `-q` to keep output clean:

```bash
./gradlew -q inspectTest [options]
```

### Options
- `--name <text>` — case-insensitive substring matched against the test name and full name. Omit to list every failing test.
- `--module <name>` — only match failures from this Gradle module
- `--lines <n>` — max stack-trace lines per failure; `0` shows the full trace (default `10`)

### Examples
```bash
# List every failing test
./gradlew -q inspectTest

# Show stack traces for matching tests
./gradlew -q inspectTest --name "should parse"

# Restrict to a single module
./gradlew -q inspectTest --name "should parse" --module app

# Show the full stack trace
./gradlew -q inspectTest --name "should parse" --lines 0
```

## Releasing

Releases are published to Maven Central via the
[`Publish plugin`](.github/workflows/publish.yml) GitHub Actions workflow, using the
[`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin. All coordinates and POM metadata come from [`gradle.properties`](gradle.properties)
(`GROUP`, `SONATYPE_HOST`, and the `POM_*` keys).

### One-time setup

Add the following repository secrets in GitHub (Settings → Secrets and variables → Actions):

- `SONATYPE_USER` / `SONATYPE_PWD` — a Central Portal user token (username + password).
- `SIGNING_KEY_ID` — the short id of the GPG signing key.
- `SIGNING_KEY` — the ASCII-armored private GPG key.
- `SIGNING_KEY_PASSPHRASE` — the passphrase for that key.

These are exposed to Gradle through `ORG_GRADLE_PROJECT_*` environment variables in the
workflow, which the publish plugin reads for authentication and in-memory signing.

### Cutting a release

1. Trigger the **Publish plugin** workflow (`workflow_dispatch`) from the `main` branch.
2. Enter the `version` to release (e.g. `0.1.0`). The version is passed to Gradle via
   `-Pversion=<version>`; without it, local builds default to `0.1.0-SNAPSHOT`.
3. The workflow runs `./gradlew assemble` and then `./gradlew publishToMavenCentral`,
   which uploads and (when signing is configured) releases to Maven Central.

### Publishing locally

```bash
# Dry-run into your local Maven repo (skips signing)
./gradlew publishToMavenLocal -Pversion=0.1.0
```

