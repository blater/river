plugins {
  application
  id("java-test-fixtures")
}

application {
  mainClass.set("io.riverdb.server.app.RiverMain")
  applicationName = "river"
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Xmx1g")
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

tasks.withType<Jar>().configureEach {
  manifest.attributes["Implementation-Version"] = project.version.toString()
}

tasks.withType<Test>().configureEach {
  maxHeapSize = "1g"
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
