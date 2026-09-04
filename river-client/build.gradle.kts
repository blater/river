// Production dependencies are declared by the root build.

dependencies {
  testImplementation(project(":river-engine"))
  testImplementation(testFixtures(project(":river-engine")))
  testImplementation(project(":river-server"))
}
