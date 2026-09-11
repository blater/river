package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverDaemonCredentialCloseFailureTest {
  private static final DatabaseIncarnation INCARNATION = DatabaseIncarnation.of(17, 29);

  @Test
  void stageCloseFailurePreventsSecurityForce(@TempDir Path root) throws Exception {
    Assumptions.assumeTrue("Mac OS X".equals(System.getProperty("os.name")));
    Path securityPath = root.toRealPath().resolve("security");
    Files.createDirectory(securityPath, PosixFilePermissions.asFileAttribute(
        PosixFilePermissions.fromString("rwx------")));
    ApfsRiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    io.riverdb.platform.riverd.RiverDirectoryResult opened =
        new io.riverdb.platform.riverd.RiverDirectoryResult();
    assertEquals(StatusCode.OK, filesystem.openDirectory(securityPath, opened));
    RiverDirectory security = opened.directory();
    RiverDaemonCredentials.CredentialResult generated =
        new RiverDaemonCredentials.CredentialResult();
    assertEquals(StatusCode.OK, RiverDaemonCredentials.generate(
        INCARNATION, 1, new SecureRandom(), Instant.now().minusSeconds(30), generated));
    RiverDaemonCredentials.Material material = generated.material();
    AtomicInteger forceCalls = new AtomicInteger();
    try {
      RiverDirectory failing = closeFailingDirectory(security, forceCalls);
      assertEquals(StatusCode.IO_FAILURE,
          RiverDaemonCredentials.publishClientConfiguration(
              material, failing, securityPath, INCARNATION, "localhost", 43117,
              "0123456789abcdef0123456789abcdef"));
      assertEquals(0, forceCalls.get());
    } finally {
      material.destroy();
      security.close();
    }
  }

  private static RiverDirectory closeFailingDirectory(
      RiverDirectory delegate, AtomicInteger forceCalls) {
    Map<RiverFile, RiverFile> rawFiles = new IdentityHashMap<>();
    return (RiverDirectory) Proxy.newProxyInstance(
        RiverDaemonCredentialCloseFailureTest.class.getClassLoader(),
        new Class<?>[] {RiverDirectory.class},
        (proxy, method, args) -> {
          if ("force".equals(method.getName())) {
            forceCalls.incrementAndGet();
          }
          if ("openFile".equals(method.getName())
              && args[1] == RiverOpenMode.CREATE_NEW) {
            StatusCode status = (StatusCode) method.invoke(delegate, args);
            if (status.isOk()) {
              RiverFileResult result = (RiverFileResult) args[2];
              RiverFile raw = result.file();
              RiverFile failing = closeFailingFile(raw);
              rawFiles.put(failing, raw);
              result.set(failing);
            }
            return status;
          }
          if ("publishExclusive".equals(method.getName())
              || "publishReplacement".equals(method.getName())) {
            Object[] callArgs = args.clone();
            callArgs[0] = rawFiles.get(callArgs[0]);
            return method.invoke(delegate, callArgs);
          }
          return method.invoke(delegate, args);
        });
  }

  private static RiverFile closeFailingFile(RiverFile delegate) {
    return (RiverFile) Proxy.newProxyInstance(
        RiverDaemonCredentialCloseFailureTest.class.getClassLoader(),
        new Class<?>[] {RiverFile.class},
        (proxy, method, args) -> {
          if (!"close".equals(method.getName())) return method.invoke(delegate, args);
          StatusCode close = (StatusCode) method.invoke(delegate, args);
          return close.isOk() ? StatusCode.IO_FAILURE : close;
        });
  }
}
