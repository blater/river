// Production dependencies are declared by the root build.

dependencies {
  testImplementation(project(":river-server-app"))
  testImplementation(testFixtures(project(":river-server-app")))
  testImplementation(project(":river-backup"))
  testImplementation(project(":river-cli"))
  testImplementation(project(":river-engine"))
  testImplementation(project(":river-protocol"))
  testImplementation(project(":river-server"))
  testImplementation(testFixtures(project(":river-client")))
}

tasks.withType<Test>().configureEach {
  systemProperty("river.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
  maxHeapSize = "1g"
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
