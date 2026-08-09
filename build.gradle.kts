import io.riverdb.buildpolicy.BuildPolicy
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.nio.file.Files

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
val hotPathPackagePrefixes = setOf(
  "io.riverdb.observability.api.event",
  "io.riverdb.wal.append",
  "io.riverdb.buffer.cache",
  "io.riverdb.storage.access",
  "io.riverdb.tx.commit",
  "io.riverdb.exec.vector"
)

val verifySourcePolicy = tasks.register("verifySourcePolicy") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Checks tabs, two-space source indentation, and internal package boundaries."

  val sourceFiles = fileTree(rootDir) {
    exclude(".git/**", ".gradle/**", ".river-gradle/**", "**/build/**")
  }
  inputs.files(sourceFiles)

  doLast {
    val checkedFiles = sourceFiles.files.map { it.toPath() }
    val javaSources = sourceFiles.files
      .filter { it.extension.equals("java", ignoreCase = true) }
      .map { file ->
        val owner = subprojects.firstOrNull { module ->
          file.toPath().toAbsolutePath().normalize().startsWith(
            module.projectDir.toPath().toAbsolutePath().normalize()
          )
        }?.name ?: "__root__"
        BuildPolicy.JavaSource(owner, file.toPath(), file.readText())
      }
    val violations = BuildPolicy.sourceViolations(
      rootDir.toPath(),
      javaSources,
      checkedFiles,
      checkedTextExtensions,
      indentedExtensions,
      hotPathPackagePrefixes
    )
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val verifyModuleGraph = tasks.register("verifyModuleGraph") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Rejects River project dependencies outside the approved module DAG."

  doLast {
    val actualGraph = linkedMapOf<String, Set<String>>()
    subprojects.forEach { module ->
      val actual = mutableSetOf<String>()
      setOf("api", "implementation", "compileOnly", "runtimeOnly").forEach {
          configurationName ->
        module.configurations.getByName(configurationName).dependencies
          .withType(ProjectDependency::class.java)
          .forEach { dependency ->
            actual.add(dependency.path.substringAfterLast(':'))
          }
      }
      actualGraph[module.name] = actual
    }
    val violations = BuildPolicy.graphViolations(actualGraph, allowedDependencies)
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val verifyBuildPolicyFixtures = tasks.register("verifyBuildPolicyFixtures") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves each build policy rejects a deterministic negative fixture."

  val fixtureDirectory = layout.buildDirectory.dir("policy-fixtures")
  outputs.dir(fixtureDirectory)

  doLast {
    fun requireViolation(name: String, violations: List<String>, expected: String) {
      if (violations.none { expected in it }) {
        throw GradleException(
          "$name fixture did not produce expected diagnostic '$expected': $violations"
        )
      }
    }

    val root = fixtureDirectory.get().asFile.toPath()
    Files.createDirectories(root)

    fun writeFixture(relative: String, content: String): java.nio.file.Path {
      val path = root.resolve(relative)
      Files.createDirectories(path.parent)
      Files.writeString(path, content)
      return path
    }

    fun sourceViolations(
      sources: List<BuildPolicy.JavaSource>,
      checkedFiles: List<java.nio.file.Path> = sources.map { it.path() },
      hotPackages: Set<String> = emptySet()
    ): List<String> = BuildPolicy.sourceViolations(
      root,
      sources,
      checkedFiles,
      checkedTextExtensions,
      indentedExtensions,
      hotPackages
    )

    val tabPath = writeFixture(
      "tab/Tab.java",
      "package fixture.tab;\n\tfinal class Tab {}\n"
    )
    requireViolation("tab", sourceViolations(emptyList(), listOf(tabPath)), "tab character")

    val indentPath = writeFixture(
      "indent/Indent.java",
      "package fixture.indent;\n final class Indent {}\n"
    )
    requireViolation(
      "indent",
      sourceViolations(emptyList(), listOf(indentPath)),
      "indentation is not a multiple of two"
    )

    val ownerPath = writeFixture(
      "internal/owner/Hidden.java",
      "package fixture.owner.internal;\npublic final class Hidden {}\n"
    )
    val consumerPath = writeFixture(
      "internal/consumer/Consumer.java",
      "package fixture.consumer;\n"
          + "import fixture.owner.internal.Hidden;\n"
          + "final class Consumer { Hidden value; }\n"
    )
    val internalSources = listOf(
      BuildPolicy.JavaSource("owner", ownerPath, Files.readString(ownerPath)),
      BuildPolicy.JavaSource("consumer", consumerPath, Files.readString(consumerPath))
    )
    requireViolation(
      "internal package",
      sourceViolations(internalSources),
      "references internal package fixture.owner.internal owned by owner"
    )

    val hotPath = writeFixture(
      "forbidden/HotLoop.java",
      "package fixture.hot;\n"
          + "import java.util.stream.IntStream;\n"
          + "final class HotLoop { IntStream values; }\n"
    )
    requireViolation(
      "forbidden API",
      sourceViolations(
        listOf(BuildPolicy.JavaSource("hot", hotPath, Files.readString(hotPath))),
        hotPackages = setOf("fixture.hot")
      ),
      "hot-path package references stream/collector APIs"
    )

    requireViolation(
      "forbidden dependency",
      BuildPolicy.graphViolations(
        mapOf("a" to setOf("b"), "b" to emptySet()),
        mapOf("a" to emptySet(), "b" to emptySet())
      ),
      "a has forbidden dependencies: [b]"
    )
    requireViolation(
      "dependency cycle",
      BuildPolicy.graphViolations(
        mapOf("a" to setOf("b"), "b" to setOf("a")),
        mapOf("a" to setOf("b"), "b" to setOf("a"))
      ),
      "module dependency cycle: a -> b -> a"
    )
  }
}

tasks.register("assembleRiverArchives") {
  group = LifecycleBasePlugin.BUILD_GROUP
  description = "Assembles every production, testkit, and benchmark JAR for comparison."
  dependsOn(subprojects.flatMap { module ->
    listOf(module.tasks.named("jar"), module.tasks.named("sourcesJar"))
  })
}

tasks.named("check") {
  dependsOn(verifySourcePolicy, verifyModuleGraph, verifyBuildPolicyFixtures)
  dependsOn(subprojects.map { it.tasks.named("check") })
}
