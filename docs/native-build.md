# Native builds

River uses Oracle GraalVM JDK 25 to build one executable for the host OS and
architecture. Set `JAVA_HOME` and `GRAALVM_HOME` to that installation.

## O3 build

```sh
./gradlew --no-daemon :river-server-app:nativeCompile
```

The result is `bin/river` (`bin/river.exe` on Windows). Runnable builds use O3; GraalVM controls the optimization level during PGO training.
The compiler's default CPU target is used: GraalVM 25.0.4 uses armv8.1-a on
AArch64 and x86-64-v3 on AMD64. The binary does not require every CPU feature
available on the build machine.

## Profile-guided build

PGO lets the compiler optimize using a representative workload. Use Oracle
GraalVM; Community Edition does not support PGO.

First build an instrumented executable:

```sh
./gradlew --no-daemon :river-server-app:nativeCompile -PriverPgoInstrument=true
```

This writes `river-server-app/build/native-instrumented/river` (with `.exe` on
Windows), keeping instrumentation out of `bin/river`.
Start it with an explicit profile destination and a temporary database:

```sh
river-server-app/build/native-instrumented/river \
  -XX:ProfilesDumpFile=/absolute/path/river.iprof \
  server start --datadir=/absolute/path/training-db --port=9191
```

Run representative SQL through the CLI or JDBC, including reads and writes.
Stop the server gracefully with Ctrl-C so it writes the profile. Instrumentation
adds overhead; do not use this executable for performance comparisons or shipping.

Build the final executable using that profile:

```sh
./gradlew --no-daemon :river-server-app:nativeCompile \
  -PriverPgoProfile=/absolute/path/river.iprof
```

The result is the ordinary standalone `bin/river`, with O3 and PGO enabled.
The profile is a build input, not a runtime dependency. Keep profiles outside Git;
refresh them when changing the workload or significant execution paths. Validate
with a separate workload seed and mix as well as the workload used for training.

Use one PGO option at a time. A missing profile file fails the build. Without
either option, the task produces an O3 build without a measured workload profile.

## Current platform validation

macOS arm64/APFS has passed native credential, SQL commit, shutdown and restart
checks. Linux ext4/XFS and Windows NTFS remain unvalidated for native execution
at this pre-alpha stage. Their support is still required; merging the current
implementation does not establish that those builds work.
