// Production dependencies are declared here because this is the launcher-owned composition.

dependencies {
  implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
  implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
  implementation("org.bouncycastle:bcutil-jdk18on:1.85")
}

tasks.withType<Test>().configureEach {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
}
