import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

plugins {
  base
}

group = "io.riverdb"
version = "0.1.0-SNAPSHOT"

val productionModules = listOf(
  "river-base",
  "river-observability-api",
  "river-platform",
  "river-format",
  "river-tx-api",
  "river-journal-api",
  "river-wal",
  "river-buffer",
  "river-storage",
  "river-tx",
  "river-recovery",
  "river-backup",
  "river-catalog",
  "river-sql",
  "river-planner",
  "river-exec",
  "river-engine-api",
  "river-engine",
  "river-protocol",
  "river-client",
  "river-server",
  "river-jdbc",
  "river-cli",
  "river-admin",
  "river-inspect",
  "river-migration",
  "river-observability"
)

val allowedDependencies = mapOf(
  "river-base" to emptySet(),
  "river-observability-api" to emptySet(),
  "river-platform" to setOf("river-observability-api"),
  "river-format" to emptySet(),
  "river-tx-api" to emptySet(),
  "river-journal-api" to setOf("river-format", "river-observability-api"),
  "river-wal" to setOf(
    "river-journal-api", "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-buffer" to setOf(
    "river-journal-api", "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-storage" to setOf(
    "river-format", "river-journal-api", "river-buffer", "river-tx-api",
    "river-observability-api"
  ),
  "river-tx" to setOf(
    "river-tx-api", "river-journal-api", "river-observability-api"
  ),
  "river-recovery" to setOf(
    "river-journal-api", "river-wal", "river-buffer", "river-storage",
    "river-tx", "river-tx-api"
  ),
  "river-backup" to setOf(
    "river-journal-api", "river-platform", "river-format", "river-wal",
    "river-buffer", "river-storage", "river-recovery"
  ),
  "river-catalog" to setOf(
    "river-storage", "river-tx-api", "river-observability-api"
  ),
  "river-sql" to setOf("river-catalog"),
  "river-planner" to setOf("river-sql", "river-catalog", "river-storage"),
  "river-exec" to setOf(
    "river-planner", "river-storage", "river-tx-api", "river-catalog",
    "river-observability-api"
  ),
  "river-engine-api" to setOf("river-base"),
  "river-engine" to setOf(
    "river-journal-api", "river-platform", "river-format", "river-wal",
    "river-buffer", "river-storage", "river-tx-api", "river-tx",
    "river-recovery", "river-backup", "river-catalog", "river-sql",
    "river-planner", "river-exec", "river-engine-api"
  ),
  "river-protocol" to setOf("river-engine-api"),
  "river-client" to setOf("river-protocol", "river-engine-api"),
  "river-server" to setOf("river-protocol", "river-engine-api", "river-engine"),
  "river-jdbc" to setOf("river-client"),
  "river-cli" to setOf("river-client"),
  "river-admin" to setOf("river-client", "river-engine-api", "river-backup"),
  "river-inspect" to setOf("river-platform", "river-format", "river-wal"),
  "river-migration" to setOf("river-client"),
  "river-observability" to setOf("river-observability-api"),
  "river-testkit" to productionModules.toSet(),
  "river-bench" to productionModules.toSet()
).mapValues { (module, dependencies) ->
  if (module == "river-base" || module == "river-observability-api") {
    dependencies
  } else {
    dependencies + "river-base"
  }
}

subprojects {
  apply(plugin = "java-library")

  group = rootProject.group
  version = rootProject.version

  extensions.configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
  }

  tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failFast = true
  }

  tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

  dependencies {
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
  }

  allowedDependencies.getValue(name).forEach { dependencyName ->
    dependencies.add("api", dependencies.project(":$dependencyName"))
  }
}

val checkedTextExtensions = setOf(
  "java", "kt", "kts", "gradle", "xml", "yml", "yaml", "json",
  "properties", "md"
)
val indentedExtensions = setOf("java", "kt", "kts", "gradle", "xml", "yml", "yaml")

val verifySourcePolicy = tasks.register("verifySourcePolicy") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Checks tabs, two-space source indentation, and internal package boundaries."

  val sourceFiles = fileTree(rootDir) {
    exclude(".git/**", ".gradle/**", "**/build/**")
  }
  inputs.files(sourceFiles)

  doLast {
    val violations = mutableListOf<String>()
    sourceFiles.files.sorted().forEach { file ->
      val extension = file.extension.lowercase()
      if (extension !in checkedTextExtensions) {
        return@forEach
      }
      file.useLines { lines ->
        lines.forEachIndexed { index, line ->
          if ('\t' in line) {
            violations.add("${file.relativeTo(rootDir)}:${index + 1}: tab character")
          }
          if (extension in indentedExtensions && line.isNotBlank()) {
            val leadingSpaces = line.indexOfFirst { it != ' ' }.let {
              if (it < 0) line.length else it
            }
            if (leadingSpaces % 2 != 0) {
              violations.add(
                "${file.relativeTo(rootDir)}:${index + 1}: indentation is not a multiple of two"
              )
            }
          }
        }
      }
      if (extension == "java" && ".internal." in file.readText()) {
        val ownInternal = file.toPath().toString().contains("/internal/")
        if (!ownInternal) {
          violations.add("${file.relativeTo(rootDir)}: references an internal package")
        }
      }
    }
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val verifyModuleGraph = tasks.register("verifyModuleGraph") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Rejects River project dependencies outside the approved module DAG."

  doLast {
    val violations = mutableListOf<String>()
    subprojects.forEach { module ->
      val allowed = allowedDependencies.getValue(module.name)
      val actual = mutableSetOf<String>()
      setOf("api", "implementation", "compileOnly", "runtimeOnly").forEach {
          configurationName ->
        module.configurations.getByName(configurationName).dependencies
          .withType(ProjectDependency::class.java)
          .forEach { dependency ->
            actual.add(dependency.path.substringAfterLast(':'))
          }
      }
      val forbidden = actual - allowed
      if (forbidden.isNotEmpty()) {
        violations.add("${module.name} has forbidden dependencies: ${forbidden.sorted()}")
      }
    }
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

tasks.named("check") {
  dependsOn(verifySourcePolicy, verifyModuleGraph)
  dependsOn(subprojects.map { it.tasks.named("check") })
}
