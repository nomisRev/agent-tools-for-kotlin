import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `kotlin-dsl`
  id("com.vanniktech.maven.publish") version "0.36.0"
}

group = providers.gradleProperty("GROUP").get()
// Version is provided via `-Pversion=<version>` (e.g. from the release workflow),
// and falls back to a SNAPSHOT for local development.
version = findProperty("version")
  .let { if (it == null || it == "unspecified") "0.1.0-SNAPSHOT" else it.toString() }

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  jvmToolchain(17)
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

gradlePlugin {
  website.set(providers.gradleProperty("POM_URL"))
  vcsUrl.set(providers.gradleProperty("POM_URL"))
  plugins {
    create("devTools") {
      id = "io.github.nomisrev.dev-tools"
      implementationClass = "devtools.DevToolsPlugin"
      displayName = "Agent Tools for Kotlin"
      description = "Adds jarSearch and inspectTest developer utility tasks."
      tags.set(listOf("kotlin", "gradle", "agent", "testing", "dependencies", "AI"))
    }
  }
}
