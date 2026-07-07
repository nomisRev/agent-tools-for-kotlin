package testreport

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
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element

/**
 * Inspect failing tests straight from Gradle's JUnit XML reports, i.e. the
 * TEST-*.xml files under each module's build/test-results directory. No intermediate
 * file or external tooling is required: run any `test` task, then query the results.
 *
 * Usage:
 *
 *     ./gradlew inspectTest                              # list every failing test
 *     ./gradlew inspectTest --name "should parse"        # show traces for matches
 *     ./gradlew inspectTest --name "should parse" --module app
 *     ./gradlew inspectTest --name "should parse" --lines 20   # 0 = unlimited
 */
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

  private data class ModuleResults(val module: String, val resultsDir: File)

  private data class TestFailure(
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
