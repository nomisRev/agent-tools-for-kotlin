// Self-contained Gradle init script that registers the `inspectTest` developer utility task on
// every project of a build and wires every `Test` task so failing test names are printed even
// with `./gradlew -q`. It needs no plugin resolution and no external dependency.
//
// The implementations below are inlined copies of the plugin sources under
// `src/main/kotlin/testreport` (InspectTestTask.kt and TestFailureListener.kt). Keep them in sync.
//
// Usage:
//   ./gradlew --init-script inspect-test.init.gradle.kts -q inspectTest
//   ./gradlew --init-script inspect-test.init.gradle.kts -q inspectTest --name "should parse"
//   ./gradlew --init-script inspect-test.init.gradle.kts -q :app:test

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element

// ---------------------------------------------------------------------------------------------
// Registration: register `inspectTest` and wire the failing-test summary on the root project
// and every subproject. Target a specific module with a task path, e.g. `:app:inspectTest`.
// ---------------------------------------------------------------------------------------------

gradle.allprojects {
  val project = this
  if (tasks.findByName("inspectTest") == null) {
    tasks.register<TestReport.InspectTestTask>("inspectTest") {
      configureFrom(project)
      group = "verification"
      description = "Inspect failing tests from build/test-results. Use --name, --module, and --lines."
    }
  }
  TestReport.TestFailureListener.applyTo(project)
}

// All implementation types live inside this single object. In a Gradle init script, top-level
// `object`s (including companion objects) may not capture the script instance, so nesting the
// whole implementation here keeps their references pointing at a stable singleton instead.
private object TestReport {

@DisableCachingByDefault(because = "Reads the latest build/test-results state; not worth caching.")
abstract class InspectTestTask : DefaultTask() {
  private lateinit var moduleResultDirs: List<ModuleResults>

  fun configureFrom(project: Project) {
    moduleResultDirs =
      project.rootProject.allprojects.map { subproject ->
        ModuleResults(
          module = subproject.name,
          resultsDir = subproject.layout.buildDirectory.dir("test-results").get().asFile,
        )
      }
  }

  @get:Input
  @get:Optional
  @get:Option(
    option = "name",
    description =
      "Case-insensitive substring matched against the test name and full name. " +
        "Omit to list every failing test.",
  )
  abstract val testName: Property<String>

  @get:Input
  @get:Optional
  @get:Option(option = "module", description = "Only match failures from this Gradle module.")
  abstract val module: Property<String>

  @get:Input
  @get:Option(
    option = "lines",
    description = "Max stack-trace lines per failure. 0 shows the full trace. Default: 10.",
  )
  abstract val traceLines: Property<Int>

  init {
    traceLines.convention(10)
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun inspect() {
    val failures = readFailures()
    if (failures.isEmpty()) {
      logger.quiet(
        "No failing tests found in any module's build/test-results. " +
          "Run a test task first, e.g. ./gradlew test.",
      )
      return
    }

    val moduleFilter = module.orNull?.trim()?.takeUnless { it.isEmpty() }
    val needle = testName.orNull?.trim()?.takeUnless { it.isEmpty() }?.lowercase()

    val matches =
      failures.filter { failure ->
        val nameMatch =
          needle == null ||
            failure.name.lowercase().contains(needle) ||
            failure.fullName.lowercase().contains(needle)
        nameMatch && (moduleFilter == null || failure.module == moduleFilter)
      }

    if (matches.isEmpty()) {
      throw GradleException(describeNoMatches(needle, moduleFilter))
    }

    // Deduplicate by module::fullName in case a test appears in more than one report.
    val deduped =
      matches
        .distinctBy { "${it.module}::${it.fullName}" }
        .sortedWith(compareBy({ it.module }, { it.fullName }))

    if (needle == null) listFailures(deduped) else printFailures(deduped)
  }

  private fun listFailures(failures: List<TestFailure>) {
    logger.quiet("${failures.size} failing test(s):\n")
    failures.forEach { logger.quiet("  ${it.label}") }
    logger.quiet("\nRe-run with --name <substring> to see a stack trace.")
  }

  private fun printFailures(failures: List<TestFailure>) {
    val maxLines = traceLines.get()
    logger.quiet("${failures.size} matching failure(s):\n")
    failures.forEach { failure ->
      logger.quiet(failure.label)
      failure.summary?.let { logger.quiet("  $it") }
      val trace = failure.trace.trim()
      if (trace.isEmpty()) {
        logger.quiet("  (no stack trace recorded)")
      } else {
        val lines = trace.split("\n")
        val shown =
          if (maxLines > 0 && lines.size > maxLines) {
            lines.take(maxLines) + "  ... (${lines.size - maxLines} more lines; use --lines 0 to show all)"
          } else {
            lines
          }
        shown.forEach { logger.quiet("  $it") }
      }
      logger.quiet("")
    }
  }

  private fun describeNoMatches(needle: String?, moduleFilter: String?): String = buildString {
    append("No failing test")
    if (needle != null) append(" matching \"${testName.get()}\"")
    if (moduleFilter != null) append(" in module \"$moduleFilter\"")
    append('.')
  }

  private fun readFailures(): List<TestFailure> =
    moduleResultDirs.flatMap { (module, dir) ->
      if (!dir.isDirectory) {
        emptyList()
      } else {
        dir.walkTopDown()
          .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
          .flatMap { xml -> parseReport(module, xml) }
          .toList()
      }
    }

  private fun parseReport(module: String, xml: File): List<TestFailure> {
    val document =
      runCatching {
          val factory =
            DocumentBuilderFactory.newInstance().apply {
              setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
              setFeature("http://xml.org/sax/features/external-general-entities", false)
              setFeature("http://xml.org/sax/features/external-parameter-entities", false)
              isNamespaceAware = false
            }
          factory.newDocumentBuilder().parse(xml)
        }
        .getOrElse {
          logger.warn("Skipping unreadable test report ${xml.path}: ${it.message}")
          return emptyList()
        }

    val root = document.documentElement ?: return emptyList()
    if (root.tagName != "testsuite" && root.tagName != "testsuites") return emptyList()

    return root.elementsByTag("testcase").flatMap { testcase ->
      val failureElements =
        testcase.childElements().filter { it.tagName == "failure" || it.tagName == "error" }
      failureElements.map { failure ->
        val name = testcase.getAttribute("name")
        val className = testcase.getAttribute("classname")
        TestFailure(
          name = name,
          fullName = if (className.isBlank()) name else "$className > $name",
          module = module,
          isError = failure.tagName == "error",
          type = failure.getAttribute("type").ifBlank { null },
          message = failure.getAttribute("message").ifBlank { null },
          trace = failure.textContent.orEmpty(),
        )
      }
    }
  }

  private fun Element.elementsByTag(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
  }

  private fun Element.childElements(): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
  }

  data class ModuleResults(val module: String, val resultsDir: File)

  data class TestFailure(
    val name: String,
    val fullName: String,
    val module: String,
    val isError: Boolean,
    val type: String?,
    val message: String?,
    val trace: String,
  ) {
    val label: String
      get() = "$fullName [$module]"

    val summary: String?
      get() {
        val kind = if (isError) "error" else "failure"
        val detail = listOfNotNull(type, message?.replace("\n", " ")).joinToString(": ")
        return if (detail.isBlank()) null else "$kind: $detail"
      }
  }
}

object TestFailureListener {
  /** Wires every `Test` task in the whole build, not just [project], so it also covers subprojects. */
  fun applyTo(project: Project) {
    project.rootProject.allprojects { tasks.withType<Test>().configureEach { registerOn(this) } }
  }

  private fun registerOn(task: Test) {
    val failures = mutableListOf<String>()

    task.addTestListener(
      object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit

        override fun beforeTest(testDescriptor: TestDescriptor) = Unit

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
          if (result.resultType == TestResult.ResultType.FAILURE) {
            val className = testDescriptor.className
            failures += if (className.isNullOrBlank()) {
              testDescriptor.name
            } else {
              "$className > ${testDescriptor.name}"
            }
          }
        }

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
          // The root suite has no parent; report once per task, not per nested suite.
          if (suite.parent != null) return

          if (failures.isEmpty()) {
            // Gradle stays silent with `-q` on a green run; print a short summary instead.
            if (result.testCount == 0L) return
            val ran = result.testCount - result.skippedTestCount
            val summary = buildString {
              append("Ran $ran test")
              if (ran != 1L) append("s")
              append(" successfully in ${task.path}.")
              if (result.skippedTestCount > 0L) {
                append(" ${result.skippedTestCount} test")
                if (result.skippedTestCount != 1L) append("s")
                append(" ignored.")
              }
            }
            task.logger.quiet("\n$summary")
            return
          }

          task.logger.quiet("\nFailed tests in ${task.path}:")
          failures.sorted().distinct().forEach { task.logger.quiet("  - $it") }
          task.logger.quiet(
            "\nRun ./gradlew -q inspectTest --name \"<test name>\" to see the stack trace.",
          )
        }
      },
    )
  }
}
}
