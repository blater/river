// Module policy and production dependencies are declared by the root build.

dependencies {
  implementation("org.openjdk.jmh:jmh-core:1.37")
  annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
  implementation("org.openjdk.jol:jol-core:0.17")
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
