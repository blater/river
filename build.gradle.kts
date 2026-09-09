import io.riverdb.buildpolicy.BuildPolicy
import io.riverdb.buildpolicy.ClassReferencePolicy
import io.riverdb.buildpolicy.LegacyEvidencePolicy
import io.riverdb.buildpolicy.ProvenancePolicy
import io.riverdb.buildpolicy.InvocationPolicy
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
version = "0.1.0-alpha.2"

val productionModules = listOf(
  "river-base",
  "river-observability-api",
  "river-platform",
  "river-format",
  "river-tx-api",
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
  "river-server-app",
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
  "river-wal" to setOf(
    "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-buffer" to setOf(
    "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-storage" to setOf(
    "river-format", "river-buffer", "river-tx-api",
    "river-observability-api"
  ),
  "river-tx" to setOf(
    "river-tx-api", "river-observability-api"
  ),
  "river-recovery" to setOf(
    "river-wal", "river-buffer", "river-storage",
    "river-tx", "river-tx-api"
  ),
  "river-backup" to setOf(
    "river-platform", "river-format", "river-wal",
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
    "river-platform", "river-format", "river-wal",
    "river-buffer", "river-storage", "river-tx-api", "river-tx",
    "river-recovery", "river-backup", "river-catalog", "river-sql",
    "river-planner", "river-exec", "river-engine-api"
  ),
  "river-protocol" to setOf("river-engine-api"),
  "river-client" to setOf("river-protocol", "river-engine-api", "river-platform"),
  "river-server" to setOf(
    "river-platform", "river-protocol", "river-engine-api", "river-engine"
  ),
  "river-server-app" to setOf(
    "river-platform", "river-protocol", "river-engine-api", "river-engine", "river-client",
    "river-server", "river-jdbc", "river-format", "river-cli"
  ),
  "river-jdbc" to setOf("river-client"),
  "river-cli" to setOf("river-client"),
  "river-admin" to setOf("river-client", "river-engine-api", "river-backup"),
  "river-inspect" to setOf("river-platform", "river-format", "river-wal"),
  "river-migration" to setOf("river-client"),
  "river-observability" to setOf("river-observability-api"),
  "river-bench" to productionModules.toSet()
).mapValues { (module, dependencies) ->
  if (module == "river-base" || module == "river-observability-api") {
    dependencies
  } else {
    dependencies + "river-base"
  }
}

val declaredDependencies = mapOf(
  "river-platform" to setOf("river-base"),
  "river-format" to setOf("river-base"),
  "river-tx-api" to setOf("river-base"),
  "river-wal" to setOf("river-base", "river-format", "river-platform"),
  "river-storage" to setOf("river-base", "river-format"),
  "river-tx" to setOf("river-base", "river-tx-api"),
  "river-backup" to setOf("river-base", "river-format", "river-platform"),
  "river-sql" to setOf("river-base"),
  "river-engine-api" to setOf("river-base"),
  "river-protocol" to setOf("river-base", "river-engine-api"),
  "river-client" to setOf("river-base", "river-engine-api", "river-protocol", "river-platform"),
  "river-server" to setOf(
    "river-base", "river-engine-api", "river-protocol"
  ),
  "river-server-app" to setOf(
    "river-base", "river-platform", "river-engine-api", "river-protocol", "river-client",
    "river-server", "river-engine", "river-jdbc", "river-format", "river-cli"
  ),
  "river-jdbc" to setOf("river-base", "river-client"),
  "river-cli" to setOf("river-base", "river-client"),
  "river-engine" to setOf(
    "river-base", "river-format", "river-platform", "river-storage",
    "river-tx", "river-tx-api", "river-wal", "river-sql", "river-engine-api"
  ),
  "river-inspect" to setOf("river-base", "river-format", "river-platform"),
  "river-bench" to setOf(
    "river-base", "river-jdbc", "river-engine-api", "river-engine", "river-server",
    "river-client", "river-platform", "river-protocol", "river-server-app"
  )
)

// Project dependencies are compile-private unless a current River consumer
// must compile against a type exposed by a dependency. Keep this allowset exact:
// adding an entry changes downstream compile visibility and requires a
// compile-visibility test.
val approvedApiDependencies = mapOf(
  "river-engine-api" to setOf("river-base"),
  "river-protocol" to setOf("river-base", "river-engine-api"),
  "river-client" to setOf("river-base", "river-engine-api", "river-platform")
)

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

  declaredDependencies.getOrDefault(name, emptySet()).forEach { dependencyName ->
    val configuration = if (
      dependencyName in approvedApiDependencies.getOrDefault(name, emptySet())
    ) {
      "api"
    } else {
      "implementation"
    }
    dependencies.add(configuration, dependencies.project(":$dependencyName"))
  }

}

val checkedTextExtensions = setOf(
  "java", "kt", "kts", "gradle", "xml", "yml", "yaml", "json",
  "properties", "md", "sh", ""
)
val indentedExtensions = setOf(
  "java", "kt", "kts", "gradle", "xml", "yml", "yaml", "sh", ""
)
val extensionlessPolicyFiles = setOf("gradlew", "verify", "verify-clean-checkout")
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
  description = "Checks source layout, package boundaries, and production/test separation."

  val sourceFiles = fileTree(rootDir) {
    exclude(".git/**", ".gradle/**", ".river-gradle/**", "**/build/**")
  }
  inputs.files(sourceFiles)

  doLast {
    val checkedFiles = sourceFiles.files
      .filter { file -> file.extension.isNotEmpty() || file.name in extensionlessPolicyFiles }
      .map { it.toPath() }
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
    subprojects.filter { module -> module.name in allowedDependencies }.forEach { module ->
      actualGraph[module.name] = BuildPolicy.inheritedProjectDependencies(
        listOfNotNull(
          module.configurations.getByName("compileClasspath"),
          module.configurations.getByName("runtimeClasspath")
        )
      ) - module.name
    }
    val violations = BuildPolicy.graphViolations(
      actualGraph,
      allowedDependencies
    ).toMutableList()
    val unknownDeclaredModules = declaredDependencies.keys - allowedDependencies.keys
    if (unknownDeclaredModules.isNotEmpty()) {
      violations.add(
        "declared dependency modules are unknown: ${unknownDeclaredModules.sorted()}"
      )
    }
    declaredDependencies.forEach { (module, dependencies) ->
      val allowed = allowedDependencies[module] ?: emptySet()
      val forbidden = dependencies - allowed
      if (forbidden.isNotEmpty()) {
        violations.add(
          "$module declares dependencies outside the maximum graph: ${forbidden.sorted()}"
        )
      }
    }
    actualGraph.forEach { (module, dependencies) ->
      val declared = declaredDependencies.getOrDefault(module, emptySet())
      val undeclared = dependencies - declared
      val stale = declared - dependencies
      if (undeclared.isNotEmpty()) {
        violations.add(
          "$module has undeclared current dependencies: ${undeclared.sorted()}"
        )
      }
      if (stale.isNotEmpty()) {
        violations.add(
          "$module has stale declared dependencies: ${stale.sorted()}"
        )
      }
    }
    val actualApiGraph = linkedMapOf<String, Set<String>>()
    subprojects.filter { module -> module.name in allowedDependencies }.forEach { module ->
      actualApiGraph[module.name] = BuildPolicy.inheritedProjectDependencies(
        listOf(module.configurations.getByName("api"))
      )
    }
    val approvedApiGraph = allowedDependencies.keys.associateWith { module ->
      approvedApiDependencies.getOrDefault(module, emptySet())
    }
    val apiViolations = BuildPolicy.graphViolations(
      actualApiGraph,
      approvedApiGraph
    ).map { violation -> "public API dependency: $violation" }
        .toMutableList()
    val unknownApiModules = approvedApiDependencies.keys - declaredDependencies.keys
    if (unknownApiModules.isNotEmpty()) {
      apiViolations.add(
        "public API dependency modules are not declared: ${unknownApiModules.sorted()}"
      )
    }
    approvedApiDependencies.forEach { (module, dependencies) ->
      val declared = declaredDependencies[module] ?: emptySet()
      val undeclared = dependencies - declared
      if (undeclared.isNotEmpty()) {
        apiViolations.add(
          "$module exports undeclared dependencies: ${undeclared.sorted()}"
        )
      }
    }
    actualApiGraph.forEach { (module, dependencies) ->
      val approved = approvedApiDependencies.getOrDefault(module, emptySet())
      val missing = approved - dependencies
      if (missing.isNotEmpty()) {
        apiViolations.add(
          "$module has stale public API dependencies: ${missing.sorted()}"
        )
      }
    }
    val allViolations = (violations + apiViolations).sorted()
    if (allViolations.isNotEmpty()) {
      throw GradleException(allViolations.joinToString(separator = "\n"))
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

    val shellTabPath = writeFixture(
      "tab/verify.sh",
      "#!/bin/sh\n\techo rejected\n"
    )
    requireViolation(
      "shell tab",
      sourceViolations(emptyList(), listOf(shellTabPath)),
      "tab character"
    )

    val extensionlessIndentPath = writeFixture(
      "indent/verify",
      "#!/bin/sh\n echo rejected\n"
    )
    requireViolation(
      "extensionless indentation",
      sourceViolations(emptyList(), listOf(extensionlessIndentPath)),
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

    val productionTestSupport = writeFixture(
      "forbidden/ProductionTestSupport.java",
      "package fixture.product;\n"
          + "import io.riverdb.testkit.Fixture;\n"
          + "final class ProductionTestSupport {\n"
          + "  void resetForTest(Fixture fixture) {}\n"
          + "}\n"
    )
    val productionTestSupportSource = BuildPolicy.JavaSource(
      "product",
      productionTestSupport,
      Files.readString(productionTestSupport),
      true
    )
    val productionTestViolations = sourceViolations(listOf(productionTestSupportSource))
    requireViolation(
      "production testkit reference",
      productionTestViolations,
      "production source references testkit code"
    )
    requireViolation(
      "production test-support identifier",
      productionTestViolations,
      "production source declares or references a test-support identifier"
    )
    requireNoViolation(
      "test-source test support",
      sourceViolations(listOf(BuildPolicy.JavaSource(
        "product",
        productionTestSupport,
        Files.readString(productionTestSupport),
        false
      )))
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

val verifyProjectDependencyVisibility = tasks.register(
  "verifyProjectDependencyVisibility"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Compiles a disposable graph to prove project edges are private by default."

  val fixtureDirectory = layout.buildDirectory.dir(
    "policy-fixtures/project-dependency-visibility"
  )
  outputs.dir(fixtureDirectory)
  inputs.files(
    rootProject.buildFile,
    rootDir.resolve("settings.gradle.kts"),
    rootDir.resolve("buildSrc/build.gradle.kts"),
    rootDir.resolve("gradle.properties"),
    rootDir.resolve("gradle/wrapper/gradle-wrapper.properties")
  )
  inputs.files(subprojects.map { it.buildFile })
  inputs.files(fileTree("buildSrc/src/main") { include("**/*.java", "**/*.kts") })
  inputs.property("gradleVersion", gradle.gradleVersion)
  inputs.property("gradleHome", gradle.gradleHomeDir?.absolutePath ?: "")
  inputs.property("javaHome", System.getProperty("java.home"))
  inputs.property("javaRuntimeVersion", System.getProperty("java.runtime.version"))
  inputs.property("javaVendor", System.getProperty("java.vendor"))
  val fixtureCompiler = project(":river-base").extensions
      .getByType<org.gradle.jvm.toolchain.JavaToolchainService>().compilerFor {
        languageVersion.set(JavaLanguageVersion.of(25))
      }
  inputs.property("fixtureCompilerHome", fixtureCompiler.map {
    it.metadata.installationPath.asFile.absolutePath
  })
  inputs.property("fixtureCompilerVersion", fixtureCompiler.map {
    it.metadata.javaRuntimeVersion
  })
  inputs.property("fixtureCompilerVendor", fixtureCompiler.map { it.metadata.vendor })

  doLast {
    val root = fixtureDirectory.get().asFile
    if (!root.deleteRecursively()) {
      throw GradleException("could not clear dependency visibility fixture $root")
    }
    Files.createDirectories(root.toPath())

    fun writeFixture(relative: String, content: String) {
      val path = root.toPath().resolve(relative)
      Files.createDirectories(path.parent)
      Files.writeString(path, content)
    }

    val catalogStorageConfiguration = if (
      "river-storage" in approvedApiDependencies.getOrDefault(
        "river-catalog",
        emptySet()
      )
    ) {
      "api"
    } else {
      "implementation"
    }
    val sqlCatalogConfiguration = if (
      "river-catalog" in approvedApiDependencies.getOrDefault(
        "river-sql",
        emptySet()
      )
    ) {
      "api"
    } else {
      "implementation"
    }

    writeFixture(
      "settings.gradle.kts",
      """
      rootProject.name = "project-dependency-visibility"
      include(
        "river-storage",
        "river-catalog",
        "river-sql",
        "approved-direct-consumer"
      )
      """.trimIndent() + "\n"
    )
    writeFixture(
      "build.gradle.kts",
      """
      import org.gradle.api.tasks.compile.JavaCompile

      subprojects {
        apply(plugin = "java-library")

        extensions.configure<JavaPluginExtension> {
          toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
          }
        }

        tasks.withType<JavaCompile>().configureEach {
          options.release.set(25)
          options.encoding = "UTF-8"
          options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }
      }

      project(":river-catalog") {
        dependencies.add(
          "$catalogStorageConfiguration",
          dependencies.project(":river-storage")
        )
      }
      project(":river-sql") {
        dependencies.add(
          "$sqlCatalogConfiguration",
          dependencies.project(":river-catalog")
        )
      }
      project(":approved-direct-consumer") {
        dependencies.add(
          "implementation",
          dependencies.project(":river-storage")
        )
      }
      """.trimIndent() + "\n"
    )
    writeFixture(
      "river-storage/src/main/java/io/riverdb/fixture/storage/StorageType.java",
      """
      package io.riverdb.fixture.storage;

      public final class StorageType {
        private StorageType() {
        }

        public static long identity() {
          return 7L;
        }
      }
      """.trimIndent() + "\n"
    )
    writeFixture(
      "river-catalog/src/main/java/io/riverdb/fixture/catalog/CatalogType.java",
      """
      package io.riverdb.fixture.catalog;

      public final class CatalogType {
        private CatalogType() {
        }

        public static long identity() {
          return 11L;
        }
      }
      """.trimIndent() + "\n"
    )
    val storageConsumer = """
      package io.riverdb.fixture.consumer;

      import io.riverdb.fixture.storage.StorageType;

      public final class StorageConsumer {
        private StorageConsumer() {
        }

        public static long identity() {
          return StorageType.identity();
        }
      }
    """.trimIndent() + "\n"
    writeFixture(
      "river-sql/src/main/java/io/riverdb/fixture/consumer/StorageConsumer.java",
      storageConsumer
    )
    writeFixture(
      "approved-direct-consumer/src/main/java/io/riverdb/fixture/consumer/StorageConsumer.java",
      storageConsumer
    )

    data class FixtureResult(val exitCode: Int, val output: String)

    fun runFixture(task: String): FixtureResult {
      val gradleHome = gradle.gradleHomeDir
          ?: throw GradleException("Gradle installation directory is unavailable")
      val gradleExecutable = gradleHome.resolve("bin/gradle")
      val compilerHome = fixtureCompiler.get().metadata.installationPath.asFile.absolutePath
      val process = ProcessBuilder(
        gradleExecutable.absolutePath,
        "--offline",
        "--no-daemon",
        "--console=plain",
        "-Porg.gradle.java.installations.paths=$compilerHome",
        "-Porg.gradle.java.installations.auto-detect=false",
        "-Porg.gradle.java.installations.auto-download=false",
        task
      )
          .directory(root)
          .redirectErrorStream(true)
          .apply {
            environment()["GRADLE_USER_HOME"] = gradle.gradleUserHomeDir.absolutePath
            environment()["JAVA_HOME"] = compilerHome
          }
          .start()
      val output = process.inputStream.bufferedReader().use { it.readText() }
      return FixtureResult(process.waitFor(), output)
    }

    val direct = runFixture(":approved-direct-consumer:compileJava")
    if (direct.exitCode != 0) {
      throw GradleException(
        "approved direct project dependency did not compile:\n${direct.output}"
      )
    }

    val transitive = runFixture(":river-sql:compileJava")
    if (transitive.exitCode == 0) {
      throw GradleException(
        "river-sql fixture compiled against river-storage solely through river-catalog"
      )
    }
    if (
      "StorageType" !in transitive.output
          || (
            "does not exist" !in transitive.output
                && "cannot find symbol" !in transitive.output
          )
    ) {
      throw GradleException(
        "transitive compilation failed for an unexpected reason:\n${transitive.output}"
      )
    }
  }
}

fun classFilesUnder(directories: Collection<java.io.File>): List<java.nio.file.Path> {
  val files = mutableListOf<java.nio.file.Path>()
  directories.sorted().forEach { directory ->
    if (!directory.isDirectory) {
      return@forEach
    }
    Files.walk(directory.toPath()).use { paths ->
      paths.filter { path ->
        Files.isRegularFile(path) && path.fileName.toString().endsWith(".class")
      }.forEach(files::add)
    }
  }
  return files.sorted()
}

val indexedTableReferenceRules = linkedMapOf(
  "io/riverdb/engine/table/IndexedTable" to setOf(
    "io/riverdb/engine/table/IndexedTableKernel"
  ),
  "io/riverdb/engine/table/IndexedTableKernel" to setOf(
    "io/riverdb/engine/table/IndexedTableStore",
    "io/riverdb/engine/table/IndexedTable"
  ),
  "io/riverdb/engine/table/IndexedTableStore" to setOf(
    "io/riverdb/engine/table/IndexedTable"
  )
)
val verifyIndexedTableClassReferences = tasks.register(
  "verifyIndexedTableClassReferences"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Enforces the compiled one-way IndexedTable -> Store -> Kernel graph."
  dependsOn(project(":river-engine").tasks.named("compileJava"))
  val engineClasses = project(":river-engine").layout.buildDirectory.dir("classes/java/main")
  inputs.files(engineClasses)

  doLast {
    val violations = ClassReferencePolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(engineClasses.get().asFile)),
      indexedTableReferenceRules
    )
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val classReferenceFixtureSources = fileTree(
  rootDir.resolve("buildSrc/src/test/resources/class-reference-policy")
) {
  include("*.java")
}
val classReferenceFixtureClasses =
  layout.buildDirectory.dir("class-reference-policy-fixtures/classes")
val compileClassReferencePolicyFixtures = tasks.register(
  "compileClassReferencePolicyFixtures"
) {
  inputs.files(classReferenceFixtureSources)
  outputs.dir(classReferenceFixtureClasses)

  doLast {
    val output = classReferenceFixtureClasses.get().asFile
    if (!output.deleteRecursively()) {
      throw GradleException("could not clear scoped class-reference fixture output $output")
    }
    Files.createDirectories(output.toPath())
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        ?: throw GradleException("a Java 25 JDK compiler is required for policy fixtures")
    val arguments = mutableListOf(
      "--release", "25",
      "-Xlint:none",
      "-d", output.absolutePath
    )
    arguments.addAll(classReferenceFixtureSources.files.map { it.absolutePath }.sorted())
    val exitCode = compiler.run(null, null, null, *arguments.toTypedArray())
    if (exitCode != 0) {
      throw GradleException("class-reference policy fixtures failed to compile: exit $exitCode")
    }
  }
}

val verifyClassReferencePolicyFixtures = tasks.register(
  "verifyClassReferencePolicyFixtures"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves every indexed-table forbidden reference edge with compiled fixtures."
  dependsOn(compileClassReferencePolicyFixtures)
  inputs.files(classReferenceFixtureClasses)

  doLast {
    val fixtureRules = linkedMapOf(
      "fixture/reference/Table" to setOf("fixture/reference/Kernel"),
      "fixture/reference/Kernel" to setOf(
        "fixture/reference/Store",
        "fixture/reference/Table"
      ),
      "fixture/reference/Store" to setOf("fixture/reference/Table")
    )
    val violations = ClassReferencePolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(classReferenceFixtureClasses.get().asFile)),
      fixtureRules
    )
    val expectedEdges = setOf(
      "fixture/reference/Table -> fixture/reference/Kernel",
      "fixture/reference/Table\$Nested -> fixture/reference/Kernel",
      "fixture/reference/Table\$DescriptorOnly -> fixture/reference/Kernel",
      "fixture/reference/Table\$TypeOperations -> fixture/reference/Kernel",
      "fixture/reference/Kernel -> fixture/reference/Store",
      "fixture/reference/Kernel -> fixture/reference/Table",
      "fixture/reference/Store -> fixture/reference/Table"
    )
    expectedEdges.forEach { edge ->
      require(violations.any { edge in it }) {
        "class-reference fixture did not prove forbidden edge $edge"
      }
    }
    require(violations.size == expectedEdges.size) {
      "class-reference fixtures produced unexpected violations: $violations"
    }
  }
}

val sqlRuntimeInvocationRules = linkedMapOf(
  "io/riverdb/engine/sql/SqlNestedQueryExecution" to setOf(
    InvocationPolicy.Invocation(
      "io/riverdb/engine/relational/RelationalSession",
      "resolveTable",
      "(Ljava/lang/CharSequence;Lio/riverdb/engine/relational/TableDefinition;)"
          + "Lio/riverdb/base/error/StatusCode;"
    ),
    InvocationPolicy.Invocation(
      "io/riverdb/engine/relational/TableDefinition",
      "findColumn",
      "(Ljava/lang/CharSequence;)I"
    )
  ),
  "io/riverdb/engine/sql/SqlQueryExecution" to setOf(
    InvocationPolicy.Invocation(
      "io/riverdb/engine/relational/TableDefinition",
      "findColumn",
      "(Ljava/lang/CharSequence;)I"
    )
  )
)
val verifySqlRuntimeInvocationPolicy = tasks.register(
  "verifySqlRuntimeInvocationPolicy"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Forbids runtime table and column resolution in SQL execution."
  dependsOn(project(":river-engine").tasks.named("compileJava"))
  val engineClasses = project(":river-engine").layout.buildDirectory.dir("classes/java/main")
  inputs.files(engineClasses)

  doLast {
    val violations = InvocationPolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(engineClasses.get().asFile)),
      sqlRuntimeInvocationRules
    )
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val invocationPolicyFixtureSources = fileTree(
  rootDir.resolve("buildSrc/src/test/resources/invocation-policy")
) {
  include("*.java")
}
val invocationPolicyFixtureClasses =
  layout.buildDirectory.dir("invocation-policy-fixtures/classes")
val compileInvocationPolicyFixtures = tasks.register(
  "compileInvocationPolicyFixtures"
) {
  inputs.files(invocationPolicyFixtureSources)
  outputs.dir(invocationPolicyFixtureClasses)

  doLast {
    val output = invocationPolicyFixtureClasses.get().asFile
    if (!output.deleteRecursively()) {
      throw GradleException("could not clear scoped invocation-policy fixture output $output")
    }
    Files.createDirectories(output.toPath())
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        ?: throw GradleException("a Java 25 JDK compiler is required for policy fixtures")
    val arguments = mutableListOf(
      "--release", "25",
      "-Xlint:none",
      "-d", output.absolutePath
    )
    arguments.addAll(invocationPolicyFixtureSources.files.map { it.absolutePath }.sorted())
    val exitCode = compiler.run(null, null, null, *arguments.toTypedArray())
    if (exitCode != 0) {
      throw GradleException("invocation policy fixtures failed to compile: exit $exitCode")
    }
  }
}

val verifyInvocationPolicyFixtures = tasks.register(
  "verifyInvocationPolicyFixtures"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves exact forbidden invocation matching with compiled fixtures."
  dependsOn(compileInvocationPolicyFixtures)
  inputs.files(invocationPolicyFixtureClasses)

  doLast {
    val fixtureRules = linkedMapOf(
      "fixture/invocation/NegativeInvocations" to setOf(
        InvocationPolicy.Invocation(
          "fixture/invocation/RelationalSession",
          "resolveTable",
          "(Ljava/lang/CharSequence;Lfixture/invocation/TableDefinition;)I"
        ),
        InvocationPolicy.Invocation(
          "fixture/invocation/TableDefinition",
          "findColumn",
          "(Ljava/lang/CharSequence;)I"
        )
      ),
      "fixture/invocation/QueryExecution" to setOf(
        InvocationPolicy.Invocation(
          "fixture/invocation/TableDefinition",
          "findColumn",
          "(Ljava/lang/CharSequence;)I"
        )
      )
    )
    val violations = InvocationPolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(invocationPolicyFixtureClasses.get().asFile)),
      fixtureRules
    )
    val expectedInvocations = setOf(
      "fixture/invocation/NegativeInvocations -> "
          + "fixture/invocation/RelationalSession.resolveTable"
          + "(Ljava/lang/CharSequence;Lfixture/invocation/TableDefinition;)I",
      "fixture/invocation/NegativeInvocations\$Nested -> "
          + "fixture/invocation/TableDefinition.findColumn(Ljava/lang/CharSequence;)I",
      "fixture/invocation/QueryExecution -> "
          + "fixture/invocation/TableDefinition.findColumn(Ljava/lang/CharSequence;)I",
      "fixture/invocation/QueryExecution\$Nested -> "
          + "fixture/invocation/TableDefinition.findColumn(Ljava/lang/CharSequence;)I"
    )
    expectedInvocations.forEach { invocation ->
      require(violations.any { invocation in it }) {
        "invocation fixture did not prove forbidden call $invocation"
      }
    }
    require(violations.size == expectedInvocations.size) {
      "invocation fixtures produced unexpected violations: $violations"
    }
  }
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
  description = "Verifies the full ledger and every resolved external artifact identity."
  dependsOn(externalDependencyReports.values)

  doLast {
    val ledgerPath = rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    val artifactRows = ProvenancePolicy.read(ledgerPath)
    ProvenancePolicy.verifyRepositoryNotices(rootDir.toPath(), artifactRows.values)

    val wrapperJarRow = artifactRows.getValue("gradle-wrapper-jar")
    val wrapperJarChecksum = sha256(rootDir.resolve("gradle/wrapper/gradle-wrapper.jar"))
    require(wrapperJarRow.sha256() == wrapperJarChecksum) {
      "Gradle wrapper JAR checksum does not match the provenance ledger"
    }
    val wrapperProperties = java.util.Properties()
    rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").inputStream().use {
      wrapperProperties.load(it)
    }
    val distributionRow = artifactRows.getValue("gradle-distribution")
    require(
      distributionRow.sha256() == wrapperProperties.getProperty("distributionSha256Sum")
    ) {
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

    ProvenancePolicy.verifyResolvedDependencies(artifactRows.values, resolved)
    ProvenancePolicy.verifyGradleMetadata(
      rootDir.toPath().resolve("gradle/verification-metadata.xml"),
      resolved
    )
  }
}

val verifyReferenceSnapshots = tasks.register("verifyReferenceSnapshots") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Explicitly verifies approved external workspace reference snapshots."

  doLast {
    val configuredRoot = providers.gradleProperty("riverReferenceWorkspaceRoot").orNull
        ?: throw GradleException(
          "verifyReferenceSnapshots requires -PriverReferenceWorkspaceRoot="
              + "/absolute/path/to/workspace"
        )
    val workspaceRoot = java.nio.file.Path.of(configuredRoot)
    require(workspaceRoot.isAbsolute) {
      "riverReferenceWorkspaceRoot must be absolute"
    }
    val rows = ProvenancePolicy.read(
      rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    )
    val identities = ProvenancePolicy.verifyExternalReferences(
      workspaceRoot,
      rows.values
    )
    identities.toSortedMap().forEach { (artifactId, identity) ->
      logger.lifecycle(
        "$artifactId ${identity.sha256()} ${identity.fileCount()} regular files"
      )
    }
    val selected = LegacyEvidencePolicy.verify(
      rootDir.toPath().resolve("docs/compatibility/legacy-support-matrix.csv"),
      workspaceRoot,
      rows
    )
    logger.lifecycle("legacy-support-matrix $selected selected files")
  }
}

val verifyProvenancePolicyFixtures = tasks.register("verifyProvenancePolicyFixtures") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Runs fail-closed provenance and snapshot negative fixtures."

  doLast {
    val canonical = Files.readAllLines(
      rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    )

    fun expectFailure(label: String, expected: String, action: () -> Unit) {
      val failure = runCatching(action).exceptionOrNull()
          ?: throw GradleException("$label fixture unexpectedly passed")
      require(expected in (failure.message ?: "")) {
        "$label fixture failed for the wrong reason: $failure"
      }
    }

    val malformedReference = canonical.map { line ->
      if (line.startsWith("legacy-ingres-source,")) {
        line.replace(
          "http://code.ingres.com/ingres/main,svn-r3970",
          "../ingres,svn-r3970"
        )
      } else {
        line
      }
    }
    expectFailure("malformed-reference", "upstream must be an absolute HTTP(S) source URL") {
      ProvenancePolicy.parse(malformedReference)
    }

    val pendingApproval = canonical.map { line ->
      if (line.startsWith("legacy-ingres-source,")) {
        line.substringBeforeLast(',') + ",pending project review"
      } else {
        line
      }
    }
    expectFailure("pending-approval", "approval is unresolved") {
      ProvenancePolicy.parse(pendingApproval)
    }

    val missingNotice = canonical.map { line ->
      if (line.startsWith("legacy-ingres-source,")) {
        line.replace(",external-file:README.txt,", ",,")
      } else {
        line
      }
    }
    expectFailure("missing-notice", "notice outcome is malformed") {
      ProvenancePolicy.parse(missingNotice)
    }

    val rows = ProvenancePolicy.parse(canonical)
    expectFailure("dependency-drift", "not resolved by the build") {
      ProvenancePolicy.verifyResolvedDependencies(rows.values, emptyMap())
    }

    val workspace = temporaryDir.toPath().resolve("workspace")
    val tree = workspace.resolve("fixture")
    Files.createDirectories(tree)
    Files.writeString(tree.resolve("README.txt"), "notice evidence\n")
    Files.writeString(tree.resolve("payload.bin"), "fixture payload\n")
    val identity = ProvenancePolicy.treeIdentity(tree)
    Files.writeString(tree.resolve(".DS_Store"), "ignored Finder metadata\n")
    require(ProvenancePolicy.treeIdentity(tree) == identity) {
      "regular .DS_Store changed the reference tree identity"
    }
    Files.writeString(tree.resolve("payload.DS_Store"), "retained near name\n")
    require(ProvenancePolicy.treeIdentity(tree) != identity) {
      "near-name metadata fixture was incorrectly excluded"
    }
    Files.delete(tree.resolve("payload.DS_Store"))
    Files.delete(tree.resolve(".DS_Store"))
    Files.createDirectory(tree.resolve(".DS_Store"))
    Files.writeString(tree.resolve(".DS_Store/retained.bin"), "retained directory\n")
    require(ProvenancePolicy.treeIdentity(tree) != identity) {
      "directory named .DS_Store was incorrectly excluded"
    }
    Files.delete(tree.resolve(".DS_Store/retained.bin"))
    Files.delete(tree.resolve(".DS_Store"))
    val metadataOnlyTree = workspace.resolve("metadata-only")
    Files.createDirectories(metadataOnlyTree)
    Files.writeString(metadataOnlyTree.resolve(".DS_Store"), "ignored Finder metadata\n")
    expectFailure("metadata-only-tree", "snapshot tree has no regular files") {
      ProvenancePolicy.treeIdentity(metadataOnlyTree)
    }
    val staleLedger = listOf(
      ProvenancePolicy.HEADER,
      listOf(
        "fixture-reference",
        "reference",
        "Fixture reference tree",
        "https://example.invalid/reference",
        "snapshot-1",
        "external-workspace:fixture",
        "0000000000000000000000000000000000000000000000000000000000000000",
        ProvenancePolicy.TREE_DIGEST,
        "LicenseRef-Fixture",
        "external-file:README.txt",
        "negative fixture",
        "external workspace reference",
        "approved:2026-08-09:project-owner-decision"
      ).joinToString(",")
    )
    val staleRows = ProvenancePolicy.parse(staleLedger)
    expectFailure("stale-digest", "reference snapshot digest is stale") {
      ProvenancePolicy.verifyExternalReferences(workspace, staleRows.values)
    }

    val matrix = temporaryDir.toPath().resolve("legacy-support-matrix.csv")
    fun selectedMatrix(path: String): List<String> =
      listOf(
        LegacyEvidencePolicy.HEADER,
        listOf(
          "fixture-reference",
          "snapshot-1",
          path,
          "0000000000000000000000000000000000000000000000000000000000000000",
          "fixture-reference",
          "negative digest fixture",
          "adapt",
          "U05",
          "selected file identity",
          "00000",
          "build-policy",
          "independent fixture"
        ).joinToString(",")
      )
    Files.write(matrix, selectedMatrix("payload.bin"))
    expectFailure("selected-file-digest", "selected source digest mismatch") {
      LegacyEvidencePolicy.verify(matrix, workspace, staleRows)
    }
    Files.write(matrix, selectedMatrix("sub/../payload.bin"))
    expectFailure("selected-file-path", "source path is not canonical") {
      LegacyEvidencePolicy.verify(matrix, workspace, staleRows)
    }
    Files.write(matrix, selectedMatrix(".DS_Store"))
    expectFailure("selected-file-exclusion", "excluded from the reference identity") {
      LegacyEvidencePolicy.verify(matrix, workspace, staleRows)
    }
  }
}

tasks.named("check") {
  dependsOn(
    verifySourcePolicy,
    verifyModuleGraph,
    verifyBuildPolicyFixtures,
    verifyProjectDependencyVisibility,
    verifyIndexedTableClassReferences,
    verifyClassReferencePolicyFixtures,
    verifySqlRuntimeInvocationPolicy,
    verifyInvocationPolicyFixtures,
    verifyDependencyLedger,
    verifyProvenancePolicyFixtures
  )
  dependsOn(subprojects.map { it.tasks.named("check") })
}
