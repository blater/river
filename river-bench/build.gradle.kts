import org.gradle.api.tasks.compile.JavaCompile

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
  testImplementation(testFixtures(project(":river-engine")))
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
