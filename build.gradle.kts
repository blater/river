import io.riverdb.buildpolicy.BuildPolicy
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
val inheritedDependencyFixture = configurations.create("policyFixtureInheritedDependency")
val inheritedClasspathFixture = configurations.create("policyFixtureCompileClasspath") {
  extendsFrom(inheritedDependencyFixture)
}
dependencies.add(
  inheritedDependencyFixture.name,
  dependencies.project(mapOf("path" to ":river-base"))
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
        val sourcePath = file.toPath().toAbsolutePath().normalize()
        val owner = subprojects.firstOrNull { module ->
          sourcePath.startsWith(
            module.projectDir.toPath().toAbsolutePath().normalize()
          )
        }
        val productionSource = owner != null && sourcePath.startsWith(
          owner.projectDir.resolve("src/main/java").toPath().toAbsolutePath().normalize()
        )
        BuildPolicy.JavaSource(
          owner?.name ?: "__root__",
          file.toPath(),
          file.readText(),
          productionSource
        )
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
      actualGraph[module.name] = BuildPolicy.inheritedProjectDependencies(
        listOf(
          module.configurations.getByName("compileClasspath"),
          module.configurations.getByName("runtimeClasspath")
        )
      )
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

    fun requireNoViolation(name: String, violations: List<String>) {
      if (violations.isNotEmpty()) {
        throw GradleException("$name fixture unexpectedly failed: $violations")
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
          + "import java.util.List;\n"
          + "final class HotLoop {\n"
          + "  List<String> values;\n"
          + "  long count() { return values.stream().count(); }\n"
          + "}\n"
    )
    requireViolation(
      "forbidden API",
      sourceViolations(
        listOf(BuildPolicy.JavaSource("hot", hotPath, Files.readString(hotPath))),
        hotPackages = setOf("fixture.hot")
      ),
      "hot-path package references stream/collector APIs"
    )

    val hotPathTest = writeFixture(
      "forbidden/test/HotLoopTest.java",
      "package fixture.hot;\n"
          + "import java.util.List;\n"
          + "final class HotLoopTest {\n"
          + "  List<String> values;\n"
          + "  long count() { return values.parallelStream().count(); }\n"
          + "}\n"
    )
    requireNoViolation(
      "test-source hot-path exclusion",
      sourceViolations(
        listOf(
          BuildPolicy.JavaSource(
            "hot",
            hotPathTest,
            Files.readString(hotPathTest),
            false
          )
        ),
        hotPackages = setOf("fixture.hot")
      )
    )

    val unicodeBypass = writeFixture(
      "forbidden/UnicodeBypass.java",
      "package fixture.hot;\n"
          + "import java.util.str\\u0065am.IntStream;\n"
          + "final class UnicodeBypass { IntStream values; }\n"
    )
    requireViolation(
      "Unicode escape bypass",
      sourceViolations(
        listOf(
          BuildPolicy.JavaSource(
            "hot",
            unicodeBypass,
            Files.readString(unicodeBypass),
            true
          )
        ),
        hotPackages = setOf("fixture.hot")
      ),
      "raw Java Unicode escape is forbidden"
    )

    requireViolation(
      "inherited custom-configuration dependency",
      BuildPolicy.graphViolations(
        mapOf(
          "fixture-consumer" to BuildPolicy.inheritedProjectDependencies(
            listOf(inheritedClasspathFixture)
          ),
          "river-base" to emptySet()
        ),
        mapOf("fixture-consumer" to emptySet(), "river-base" to emptySet())
      ),
      "fixture-consumer has forbidden dependencies: [river-base]"
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

val expectedArchiveList = layout.buildDirectory.file("reports/expected-archives.paths")
val expectedArchiveCount = layout.buildDirectory.file("reports/expected-archives.count")
val writeExpectedArchiveList = tasks.register("writeExpectedArchiveList") {
  outputs.files(expectedArchiveList, expectedArchiveCount)

  doLast {
    val archivePaths = subprojects.flatMap { module ->
      listOf(
        module.tasks.named<Jar>("jar").get().archiveFile.get().asFile,
        module.tasks.named<Jar>("sourcesJar").get().archiveFile.get().asFile
      )
    }.map { archive ->
      rootDir.toPath().toAbsolutePath().normalize()
        .relativize(archive.toPath().toAbsolutePath().normalize())
        .toString()
        .replace(java.io.File.separatorChar, '/')
    }.sorted()
    val listPath = expectedArchiveList.get().asFile.toPath()
    val countPath = expectedArchiveCount.get().asFile.toPath()
    Files.createDirectories(listPath.parent)
    Files.write(listPath, archivePaths)
    Files.writeString(countPath, "${archivePaths.size}\n")
  }
}

tasks.register("assembleRiverArchives") {
  group = LifecycleBasePlugin.BUILD_GROUP
  description = "Assembles every production, testkit, and benchmark JAR for comparison."
  dependsOn(writeExpectedArchiveList)
  dependsOn(subprojects.flatMap { module ->
    listOf(module.tasks.named("jar"), module.tasks.named("sourcesJar"))
  })
}

tasks.named("check") {
  dependsOn(verifySourcePolicy, verifyModuleGraph, verifyBuildPolicyFixtures)
  dependsOn(subprojects.map { it.tasks.named("check") })
}
