plugins {
  application
}

application {
  mainClass.set("io.riverdb.server.app.RiverdMain")
  applicationName = "riverd"
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Xmx1g")
}

dependencies {
  implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
  implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
  implementation("org.bouncycastle:bcutil-jdk18on:1.85")
  testImplementation(project(":river-jdbc"))
}

tasks.withType<Jar>().configureEach {
  manifest.attributes["Implementation-Version"] = project.version.toString()
}

tasks.withType<Test>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
