plugins {
  id("java-test-fixtures")
}

// Production dependencies are declared by the root build.

dependencies {
  testFixturesImplementation(project(":river-base"))
  testFixturesImplementation(project(":river-platform"))
  testImplementation(project(":river-engine"))
  testImplementation(testFixtures(project(":river-client")))
}
