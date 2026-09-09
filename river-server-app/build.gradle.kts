plugins {
  id("java-test-fixtures")
}

val riverMainClass = "io.riverdb.server.app.RiverMain"
val riverJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Xmx1g")
val applicationVersion = project.version.toString()
val hostOs = System.getProperty("os.name")
val windowsHost = hostOs.startsWith("Windows", ignoreCase = true)
val nativePlatform = when {
  windowsHost -> "windows"
  hostOs.startsWith("Mac", ignoreCase = true) -> "macos"
  hostOs.startsWith("Linux", ignoreCase = true) -> "linux"
  else -> throw GradleException("nativeCompile is unsupported on host OS: $hostOs")
}
val nativeConfigurationDirectory = layout.projectDirectory.dir("src/main/native/$nativePlatform")
val nativeExecutableName = if (windowsHost) "river.exe" else "river"
val nativeImageOutput = rootProject.layout.projectDirectory.file("bin/$nativeExecutableName")
val graalVmHome = providers.environmentVariable("GRAALVM_HOME").orElse("")
val nativeImageCommand = if (windowsHost) "native-image.cmd" else "native-image"
val nativeImageExecutable = graalVmHome.map { file("$it/bin/$nativeImageCommand") }
val nativeImageOptions = listOf(
  "--no-fallback",
  "--enable-native-access=ALL-UNNAMED",
  "--enable-all-security-services",
  "--future-defaults=run-time-initialize-security-providers",
  "--initialize-at-run-time="
      + "io.riverdb.platform.riverd.apfs.DarwinFileBridge,"
      + "io.riverdb.platform.riverd.linux.LinuxFileBridge,"
      + "io.riverdb.platform.riverd.ntfs.WindowsFileBridge",
  "-R:MaxHeapSize=1g",
  "-H:+ReportExceptionStackTraces"
)
val generatedVersionRoot = layout.buildDirectory.dir("generated-resources/version")
val generatedVersionFile = generatedVersionRoot.map {
  it.file("io/riverdb/server/app/version.properties")
}
val generateVersionResource = tasks.register("generateVersionResource") {
  inputs.property("applicationVersion", applicationVersion)
  outputs.file(generatedVersionFile)
  doLast {
    val file = generatedVersionFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText("river.version=$applicationVersion\n")
  }
}

sourceSets.main.get().resources.srcDir(generatedVersionRoot)
tasks.named("processResources") {
  dependsOn(generateVersionResource)
}

tasks.register<JavaExec>("run") {
  group = "application"
  description = "Runs RiverMain on the JVM."
  dependsOn(tasks.named("classes"))
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set(riverMainClass)
  jvmArgs(*riverJvmArgs.toTypedArray())
  standardInput = System.`in`
}

tasks.named<Delete>("clean") {
  delete(nativeImageOutput)
}

tasks.register<Exec>("nativeCompile") {
  group = "build"
  description = "Builds the standalone River executable for this host."
  dependsOn(tasks.named("classes"))
  val runtimeClasspath = configurations.runtimeClasspath
  val nativeClasspath = files(sourceSets.main.get().output, runtimeClasspath)
  val output = nativeImageOutput.asFile
  inputs.files(runtimeClasspath, sourceSets.main.get().output)
  inputs.property("applicationVersion", applicationVersion)
  inputs.property("mainClass", riverMainClass)
  inputs.property("nativeImageOptions", nativeImageOptions)
  outputs.file(nativeImageOutput)
  doFirst {
    if (graalVmHome.get().isBlank()) {
      throw GradleException("nativeCompile requires GRAALVM_HOME to point to GraalVM JDK 25")
    }
    val image = nativeImageExecutable.get()
    if (!image.isFile) {
      throw GradleException("GRAALVM_HOME does not contain $nativeImageCommand: ${image.absolutePath}")
    }
    output.parentFile.mkdirs()
    if (windowsHost) {
      executable("cmd.exe")
      args("/c", image.absolutePath)
    } else {
      executable(image)
    }
    args(
      "-H:ConfigurationFileDirectories=${nativeConfigurationDirectory.asFile.absolutePath}",
      *nativeImageOptions.toTypedArray(),
      "-cp",
      nativeClasspath.asPath,
      "-o",
      output.absolutePath,
      riverMainClass
    )
  }
  inputs.dir(nativeConfigurationDirectory)
  inputs.property("graalVmHome", graalVmHome)
  inputs.property("nativeImageExecutable", nativeImageExecutable.map { it.absolutePath })
}

dependencies {
  testFixturesImplementation(project(":river-base"))
  testFixturesImplementation(project(":river-platform"))
  testFixturesImplementation(project(":river-engine"))
  testFixturesImplementation(project(":river-server"))
  implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
  implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
  implementation("org.bouncycastle:bcutil-jdk18on:1.85")
  testImplementation(project(":river-jdbc"))
}

tasks.withType<Test>().configureEach {
  maxHeapSize = "1g"
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
