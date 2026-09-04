plugins {
  `java-test-fixtures`
}

// Module policy and dependencies are declared by the root build.
dependencies {
  testFixturesImplementation(project(":river-base"))
}
