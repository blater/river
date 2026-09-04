plugins {
  application
}

// Module policy and production dependencies are declared by the root build.

application {
  mainClass.set("io.riverdb.cli.RiverSqlMain")
}

dependencies {
  testImplementation(project(":river-engine"))
  testImplementation(testFixtures(project(":river-engine")))
  testImplementation(project(":river-protocol"))
  testImplementation(project(":river-server"))
}
