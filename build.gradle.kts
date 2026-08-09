import io.riverdb.buildpolicy.BuildPolicy
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.nio.file.Files
import java.security.MessageDigest

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

fun sha256(file: java.io.File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) {
        break
      }
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val externalDependencyReports = subprojects.associateWith { module ->
  val report = module.layout.buildDirectory.file("reports/external-dependencies.tsv")
  module.tasks.register("writeExternalDependencyReport") {
    outputs.file(report)
    outputs.upToDateWhen { false }

    doLast {
      val resolved = sortedMapOf<String, String>()
      module.configurations.filter { it.isCanBeResolved }.forEach { configuration ->
        val recognizedFiles = mutableSetOf<java.nio.file.Path>()
        configuration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
          recognizedFiles.add(artifact.file.toPath().toAbsolutePath().normalize())
          val component = artifact.moduleVersion.id
          val identifier = artifact.id.componentIdentifier
          if (identifier is ModuleComponentIdentifier) {
            require(artifact.extension == "jar" && artifact.classifier.isNullOrBlank()) {
              "external artifact classifiers/extensions are unsupported by ledger v1: " +
                  "${component.group}:${component.name}:${component.version}:" +
                  "${artifact.classifier}:${artifact.extension}"
            }
            val key = "${component.group}:${component.name}:${component.version}"
            val checksum = sha256(artifact.file)
            val previous = resolved.putIfAbsent(key, checksum)
            require(previous == null || previous == checksum) {
              "external dependency $key resolved to different bytes in ${module.path}"
            }
          } else {
            require(identifier is ProjectComponentIdentifier) {
              "unsupported non-module dependency in ${module.path}:${configuration.name}: " +
                  identifier.displayName
            }
          }
        }
        val untrackedFiles = configuration.resolve()
            .map { it.toPath().toAbsolutePath().normalize() }
            .filterNot { recognizedFiles.contains(it) }
            .sorted()
        require(untrackedFiles.isEmpty()) {
          "file/self-resolving dependencies are unsupported by ledger v1 in " +
              "${module.path}:${configuration.name}: $untrackedFiles"
        }
      }
      val reportPath = report.get().asFile.toPath()
      Files.createDirectories(reportPath.parent)
      Files.write(
        reportPath,
        resolved.map { (coordinate, checksum) -> "$coordinate\t$checksum" }
      )
    }
  }
}

val verifyDependencyLedger = tasks.register("verifyDependencyLedger") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Verifies every resolved external JAR against the provenance ledger."
  dependsOn(externalDependencyReports.values)

  doLast {
    val ledgerPath = rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    val ledgerLines = Files.readAllLines(ledgerPath)
    require(ledgerLines.isNotEmpty()) { "provenance ledger is empty" }
    require(
      ledgerLines.first()
          == "artifact_id,artifact_type,name,upstream,version,sha256,license,use,vendoring,approval"
    ) { "provenance ledger header does not match the v1 schema" }

    val artifactRows = linkedMapOf<String, List<String>>()
    val dependencyRows = linkedMapOf<String, List<String>>()
    ledgerLines.drop(1).forEachIndexed { index, line ->
      val fields = line.split(',')
      require(fields.size == 10) {
        "provenance ledger line ${index + 2} has ${fields.size} fields, expected 10"
      }
      require(artifactRows.put(fields[0], fields) == null) {
        "duplicate provenance artifact ID ${fields[0]}"
      }
      if (fields[1] == "dependency" || fields[1] == "tool") {
        require(
          fields[0].isNotBlank()
              && fields[2].isNotBlank()
              && fields[3].isNotBlank()
              && fields[4].isNotBlank()
              && fields[5].matches(Regex("[0-9a-f]{64}"))
              && fields[6].isNotBlank()
              && fields[7].isNotBlank()
              && fields[8].isNotBlank()
              && fields[9].isNotBlank()
        ) { "provenance ${fields[1]} row is incomplete at line ${index + 2}" }
      }
      if (fields[1] == "dependency") {
        require(fields[2].count { it == ':' } == 1) {
          "provenance dependency coordinate must be group:name at line ${index + 2}"
        }
        val key = "${fields[2]}:${fields[4]}"
        require(dependencyRows.put(key, fields) == null) {
          "duplicate provenance dependency $key"
        }
      }
    }

    val wrapperJarRow = artifactRows.getValue("gradle-wrapper-jar")
    val wrapperJarChecksum = sha256(rootDir.resolve("gradle/wrapper/gradle-wrapper.jar"))
    require(wrapperJarRow[5] == wrapperJarChecksum) {
      "Gradle wrapper JAR checksum does not match the provenance ledger"
    }
    val wrapperProperties = java.util.Properties()
    rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").inputStream().use {
      wrapperProperties.load(it)
    }
    val distributionRow = artifactRows.getValue("gradle-distribution")
    require(distributionRow[5] == wrapperProperties.getProperty("distributionSha256Sum")) {
      "Gradle distribution checksum does not match wrapper properties"
    }

    val resolved = sortedMapOf<String, String>()
    externalDependencyReports.values.forEach { reportTask ->
      val reportPath = reportTask.get().outputs.files.singleFile.toPath()
      Files.readAllLines(reportPath).forEach { line ->
        val fields = line.split('\t')
        require(fields.size == 2) { "invalid external dependency report line: $line" }
        val previous = resolved.putIfAbsent(fields[0], fields[1])
        require(previous == null || previous == fields[1]) {
          "external dependency ${fields[0]} resolved to multiple checksums"
        }
      }
    }

    val missingRows = resolved.keys - dependencyRows.keys
    val staleRows = dependencyRows.keys - resolved.keys
    require(missingRows.isEmpty()) {
      "resolved dependencies missing from provenance ledger: ${missingRows.sorted()}"
    }
    require(staleRows.isEmpty()) {
      "provenance dependency rows are not resolved by the build: ${staleRows.sorted()}"
    }

    resolved.forEach { (key, actual) ->
      val expected = dependencyRows.getValue(key)[5]
      require(actual == expected) {
        "provenance checksum mismatch for $key: expected $expected, got $actual"
      }
    }
  }
}

tasks.named("check") {
  dependsOn(
    verifySourcePolicy,
    verifyModuleGraph,
    verifyBuildPolicyFixtures,
    verifyDependencyLedger
  )
  dependsOn(subprojects.map { it.tasks.named("check") })
}
