import com.vanniktech.maven.publish.SonatypeHost

plugins {
  `kotlin-dsl`
  id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.nomisrev"
version = "0.1.0"

repositories {
  mavenCentral()
}

gradlePlugin {
  plugins {
    create("devTools") {
      id = "io.github.nomisrev.dev-tools"
      implementationClass = "devtools.DevToolsPlugin"
      displayName = "Dev Tools"
      description = "Adds jarSearch and inspectTest developer utility tasks."
    }
  }
}

mavenPublishing {
  publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
  signAllPublications()

  coordinates(group.toString(), "dev-tools-gradle-plugin", version.toString())

  pom {
    name.set("Dev Tools Gradle Plugin")
    description.set("Gradle tasks for searching dependency jars and inspecting test failures.")
    url.set("https://github.com/nomisRev/ktor-arrow-example")
    inceptionYear.set("2024")

    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }

    developers {
      developer {
        id.set("nomisRev")
        name.set("Simon Vergauwen")
        url.set("https://github.com/nomisRev")
      }
    }

    scm {
      url.set("https://github.com/nomisRev/ktor-arrow-example")
      connection.set("scm:git:git://github.com/nomisRev/ktor-arrow-example.git")
      developerConnection.set("scm:git:ssh://git@github.com/nomisRev/ktor-arrow-example.git")
    }
  }
}
