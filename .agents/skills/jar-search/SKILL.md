---
name: jar-search
description: Search dependency jars (packages, types, functions, methods, bytecode) on a Gradle build's classpath using the self-contained jar-search.init.gradle.kts init script, without needing the dev-tools plugin applied to the target project. Use whenever you need to know what a Kotlin/Java dependency actually exposes — e.g. to check a type exists, find its members or bytecode, or discover the right method/function signature before writing code against it.
---

# Jar Search

Search packages, types, functions, methods, and bytecode in resolved dependency jars via the `jarSearch`
task, applied through the self-contained init script
[`jar-search.init.gradle.kts`](jar-search.init.gradle.kts). No plugin resolution, network
access, or version pinning is required, and no target project build file needs to be modified.

Always pass `-q`/`--quiet` to `./gradlew` — the task is designed to produce clean,
token-efficient output only under quiet mode.

## Setup

This skill bundles a self-contained copy of `jar-search.init.gradle.kts` (see
[jar-search.init.gradle.kts](jar-search.init.gradle.kts)). Point `--init-script` at its
absolute path from the target repository — no plugin resolution, network access, or copying
into the target project is required:

```bash
./gradlew --init-script /absolute/path/to/.agents/skills/jar-search/jar-search.init.gradle.kts -q jarSearch --dependency <spec> [options]
```

The init script registers `jarSearch` on the root project and every subproject. Target a
specific module with a task path, e.g. `:app:jarSearch`.

## Workflow

1. List packages/types available in a dependency (by version-catalog alias, direct Gradle
   coordinate, or jar path):

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/jar-search/jar-search.init.gradle.kts -q jarSearch --dependency arrow-core
   ```

2. Look up a specific type — an exact name match also dumps all of its declared members:

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/jar-search/jar-search.init.gradle.kts -q jarSearch --dependency arrow-core --kind type --query Either
   ```

3. Find functions/methods matching a name across a dependency's jars:

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/jar-search/jar-search.init.gradle.kts -q jarSearch --dependency "io.arrow-kt:arrow-core" --kind method --query fold
   ```

4. Disassemble exact types without manually locating their cached jar. Separate multiple
   fully-qualified type names with commas:

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/jar-search/jar-search.init.gradle.kts -q jarSearch \
     --dependency "org.apache.kafka:kafka-clients:3.9.0" \
     --kind bytecode \
     --query "org.apache.kafka.clients.admin.FeatureUpdate,org.apache.kafka.clients.admin.FeatureMetadata"
   ```

5. Search the whole classpath (e.g. when you don't know which dependency declares a type) by
   passing `*` or a configuration name:

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/jar-search/jar-search.init.gradle.kts -q jarSearch --dependency '*' --kind type --query Either
   ```

6. Target a specific module in a multi-module build with a task path:

   ```bash
   ./gradlew --init-script /absolute/path/to/.agents/skills/jar-search/jar-search.init.gradle.kts -q :app:jarSearch --dependency arrow-core
   ```

## Options

### `--dependency` (required)
One of:
- `*` or a configuration name — search every jar on that configuration
- a Gradle coordinate `group:artifact[:version]`
- a version-catalog alias (e.g. `arrow-core`)
- a direct `.jar` path

### Other options
- `--query <text>` — package, type, function, method, or bytecode type query
- `--kind <all|package|type|function|top_level_function|method|bytecode>` — default `all`
  (`function`, `top_level_function`, `method`, and `bytecode` require `--query`)
  `bytecode` runs `javap -c -p` for exact comma-separated type names.
- `--configuration <name>` — configuration to search/resolve against (default `compileClasspath`)
- `--limit <n>` — max results per section (default `20`)
- `--include-non-public` — include non-public members
- `--include-synthetic` — include synthetic/compiler-generated members
- `--raw-signatures` — print raw JVM descriptors
- `--transitive <auto|true|false>` — transitive search mode (default `auto`); `auto` searches
  the matching jar(s) first and falls back to the full configuration when a member query has
  no results

## Notes

- When `--kind type` (or `all`) and `--query` exactly matches a type's simple or fully-qualified
  name, all of that type's declared members are listed automatically — no need for a second
  `--kind method` call in that case.
- If the target project already applies the `io.github.nomisrev.dev-tools` Gradle plugin (see
  the plugin's [README](https://github.com/nomisRev/agent-tools-for-kotlin#readme)), the
  `jarSearch` task is already available and the `--init-script` flag can be skipped.
- The plugin also provides an `inspectTest` task (via `inspect-test.init.gradle.kts`) for
  reading failing tests from JUnit XML reports — unrelated to jar searching.
