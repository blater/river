// Production dependencies are declared by the root build.

dependencies {
  testImplementation(project(":river-backup"))
  testImplementation(project(":river-cli"))
  testImplementation(project(":river-engine"))
  testImplementation(testFixtures(project(":river-engine")))
  testImplementation(project(":river-protocol"))
  testImplementation(project(":river-server"))
}
