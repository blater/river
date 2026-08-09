plugins {
  `java-test-fixtures`
}

dependencies {
  "testFixturesApi"("org.junit.jupiter:junit-jupiter-api:5.13.4")
  "testFixturesImplementation"(project(":river-base"))
  "testFixturesImplementation"(project(":river-platform"))
  "testFixturesImplementation"(project(":river-tx-api"))
  "testFixturesImplementation"(project(":river-journal-api"))
}
