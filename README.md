# Agent Tools For Kotlin

[![Maven Central](https://img.shields.io/maven-central/v/io.github.nomisrev/dev-tools-gradle-plugin?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.nomisrev/dev-tools-gradle-plugin)

This repository aims to make coding agents more efficient with Kotlin/Gradle by fixing two common issues:

 - Searching dependency jars and their available types, functions, members, and bytecode, without manually locating Gradle cache entries. See [JarSearchTask](src/main/kotlin/jarsearch/JarSearchTask.kt).
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
  id("io.github.nomisrev.dev-tools") version "0.0.1"
}
```

### Using the version catalog (`gradle/libs.versions.toml`)

```toml
[plugins]
dev-tools = { id = "io.github.nomisrev.dev-tools", version = "0.0.1" }
```

```kotlin
plugins {
  alias(libs.plugins.dev.tools)
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
- `--query <text>` — package, type, function, method, or bytecode type query
- `--kind <all|package|type|function|top_level_function|method|bytecode>` — default `all`
  (`function`, `top_level_function`, `method`, and `bytecode` require `--query`)
  `bytecode` runs `javap -c -p` for exact comma-separated type names.
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

# Disassemble one or more exact types (equivalent to javap -c -p)
./gradlew -q jarSearch --dependency "org.apache.kafka:kafka-clients:3.9.0" \\
  --kind bytecode \\
  --query "org.apache.kafka.clients.admin.FeatureUpdate,org.apache.kafka.clients.admin.FeatureMetadata"
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
