import org.gradle.api.tasks.compile.JavaCompile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

// Module policy and production dependencies are declared by the root build.

dependencies {
  implementation(project(":river-engine"))
  implementation(project(":river-engine-api"))
  implementation(project(":river-server"))
  implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
  implementation("org.hdrhistogram:HdrHistogram:2.2.2")
  implementation("org.openjdk.jmh:jmh-core:1.37")
  annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
  implementation("org.openjdk.jol:jol-core:0.17")
  testImplementation(project(":river-engine"))
  testImplementation(project(":river-engine-api"))
  testImplementation(project(":river-server"))
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.add("-Xlint:-processing")
}

tasks.register("riverHarnessRuntimeClasspath") {
  group = "verification"
  description = "Builds and reports the complete runtime classpath used by river-harness."
  dependsOn(configurations.runtimeClasspath)
  doLast {
    println("RIVER_HARNESS_CLASSPATH=" + sourceSets.main.get().runtimeClasspath.asPath)
  }
}

val riverTpsClasspathOutput = providers.gradleProperty("riverTpsClasspathOutput")
val riverTpsBuildId = providers.gradleProperty("riverTpsBuildId")

tasks.register("writeRiverTpsRuntimeClasspath") {
  group = "verification"
  description = "Builds and writes the authoritative TPS launch classpath."
  dependsOn(tasks.named("classes"), configurations.runtimeClasspath)
  inputs.property("buildId", riverTpsBuildId)
  outputs.file(riverTpsClasspathOutput)
  doLast {
    val output = file(riverTpsClasspathOutput.get())
    val entries = sourceSets.main.get().runtimeClasspath.files.toList()
    val buildId = riverTpsBuildId.get()
    check(buildId.matches(Regex("[0-9a-f]{64}"))) { "invalid TPS build identity" }
    // make.sh owns the admitted invocation and retains its fixed argv. Gradle
    // also synthesizes default values in systemPropertiesArgs, so that map
    // cannot distinguish user -D input; environment/configuration injection
    // remains unsupported below.
    val externalConfiguration = gradle.startParameter.allInitScripts.isNotEmpty()
        || gradle.gradleUserHomeDir.resolve("gradle.properties").exists()
        || gradle.gradleHomeDir?.resolve("gradle.properties")?.exists() == true
        || gradle.startParameter.projectProperties.keys.any {
          it != "riverTpsClasspathOutput" && it != "riverTpsBuildId"
        }
        || System.getenv().any { (name, value) ->
          value.isNotEmpty() && (name.startsWith("ORG_GRADLE_PROJECT_")
              || name in setOf("GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS",
                  "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
        }
    val fields = buildList {
      add("schema=river-tps-runtime-v3")
      add("build.id=$buildId")
      add("build.inputs=${if (externalConfiguration) "unsupported" else "workspace_declared"}")
      add("build.cache_trust=gradle_declared_inputs")
      add("gradle.version=${gradle.gradleVersion}")
      add("gradle.home=${gradle.gradleHomeDir?.absolutePath ?: "unavailable"}")
      add("gradle.user.home=${gradle.gradleUserHomeDir.absolutePath}")
      add("gradle.process.pid=${ProcessHandle.current().pid()}")
      add("java.home=${System.getProperty("java.home")}")
      add("java.version=${System.getProperty("java.version")}")
      rootProject.subprojects.sortedBy { it.path }.forEach { module ->
        val compile = module.tasks.named<JavaCompile>("compileJava").get()
        val compiler = compile.javaCompiler.get()
        val prefix = "compiler.${module.name}"
        add("$prefix.home=${compiler.metadata.installationPath.asFile.absolutePath}")
        add("$prefix.version=${compiler.metadata.javaRuntimeVersion}")
        add("$prefix.executable=${compiler.executablePath.asFile.absolutePath}")
        val executableDigest = MessageDigest.getInstance("SHA-256")
            .digest(compiler.executablePath.asFile.readBytes())
        add("$prefix.launcher_sha256=${HexFormat.of().formatHex(executableDigest)}")
        val options = listOf(compile.options.release.orNull.toString(),
            compile.options.encoding.orEmpty()) + compile.options.allCompilerArgs
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(options.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))
        add("$prefix.selected_options_sha256=${HexFormat.of().formatHex(digest)}")
      }
      entries.forEach { entry ->
        val path = entry.absolutePath
        check('\n' !in path && '\r' !in path) {
          "runtime classpath entry contains a line break"
        }
        add("classpath=$path")
      }
    }
    output.parentFile.mkdirs()
    output.writeText(fields.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
  }
}

tasks.register<JavaExec>("benchmarkSmoke") {
  group = "verification"
  description = "Writes one immutable local-only benchmark harness smoke."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("io.riverdb.bench.harness.BenchmarkSmoke")
  args(layout.buildDirectory.dir("benchmark-smoke").get().asFile.absolutePath)
}

tasks.register<JavaExec>("workloadSmoke") {
  group = "verification"
  description = "Writes one developer-only streaming workload artifact."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("io.riverdb.bench.harness.WorkloadGenerationSmoke")
  args(layout.buildDirectory.dir("workload-smoke").get().asFile.absolutePath)
}

tasks.register<JavaExec>("prototypeSmoke") {
  group = "verification"
  description = "Runs short, developer-only P09 prototype measurements."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("io.riverdb.bench.prototype.PrototypeSmoke")
  args(layout.buildDirectory.dir("prototype-smoke").get().asFile.absolutePath)
}

tasks.register<JavaExec>("jmhSmoke") {
  group = "verification"
  description = "Runs one short, developer-only JMH mechanism smoke."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("org.openjdk.jmh.Main")
  args(
    "io.riverdb.bench.prototype.MechanismBenchmark",
    "-f", "1",
    "-wi", "1",
    "-i", "1",
    "-w", "100ms",
    "-r", "100ms"
  )
}

tasks.register<JavaExec>("tpccAcceptance") {
  group = "verification"
  description = "Runs the JDBC-only one-warehouse TPC-C engineering acceptance."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("io.riverdb.bench.tpcc.TpccAcceptanceMain")
  val riverUrl = providers.gradleProperty("riverTpccUrl")
  args("--url=${riverUrl.orNull ?: "jdbc:river://localhost:54321"}")
}
