// Module policy and dependencies are declared by the root build.

tasks.withType<Test>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
