package devtools

import jarsearch.JarSearchTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import testreport.InspectTestTask
import testreport.TestFailureListener

/**
 * Registers developer utility tasks:
 * - `jarSearch`: search packages, types, and members in dependency jars.
 * - `inspectTest`: inspect failing tests from build/test-results.
 *
 * Also wires every `Test` task so failing test names are printed even with `./gradlew -q`.
 */
class DevToolsPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.tasks.register<JarSearchTask>("jarSearch") {
      configureFrom(target)
      group = "help"
      description = "Search packages, types, and members in dependency jars."
    }

    target.tasks.register<InspectTestTask>("inspectTest") {
      configureFrom(target)
      group = "verification"
      description = "Inspect failing tests from build/test-results. Use --name, --module, and --lines."
    }

    TestFailureListener.applyTo(target)
  }
}
