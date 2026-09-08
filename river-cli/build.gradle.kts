plugins {
  application
}

// Module policy and production dependencies are declared by the root build.

application {
  mainClass.set("io.riverdb.cli.RiverSqlMain")
}

dependencies {
  testImplementation(project(":river-server-app"))
  testImplementation(testFixtures(project(":river-server-app")))
  testImplementation(project(":river-engine"))
  testImplementation(project(":river-protocol"))
  testImplementation(project(":river-server"))
  testImplementation(testFixtures(project(":river-client")))
}

tasks.withType<Test>().configureEach {
  maxHeapSize = "1g"
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
