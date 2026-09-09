package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
final class RiverDaemonPathsTest {
  private static final Set<PosixFilePermission> PRIVATE = Set.of(
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE);

  @Test
  void verifiedPreflightAcceptsIndependentMissingLeaves(@TempDir Path root) throws Exception {
    root = root.toRealPath();
    Files.setPosixFilePermissions(root, PRIVATE);
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    paths.datadir = root.resolve("instance");
    paths.runtimeRoot = root.resolve("runtimeRoot");
    paths.ready = root.resolve("ready").resolve("riverd.ready");
    RiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    assertEquals(StatusCode.OK, RiverDaemonPaths.verify(filesystem, paths));
  }

  @Test
  void verifiedPreflightRejectsExistingReadyDirectory(@TempDir Path root) throws Exception {
    root = root.toRealPath();
    Files.setPosixFilePermissions(root, PRIVATE);
    Path ready = root.resolve("ready");
    Files.createDirectory(ready);
    Files.setPosixFilePermissions(ready, PRIVATE);
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    paths.datadir = root.resolve("instance");
    paths.runtimeRoot = root.resolve("runtimeRoot");
    paths.ready = ready;
    RiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, RiverDaemonPaths.verify(filesystem, paths));
  }

  @Test
  void verifiedPreflightRejectsReadySymlinkAlias(@TempDir Path root) throws Exception {
    root = root.toRealPath();
    Files.setPosixFilePermissions(root, PRIVATE);
    Path target = root.resolve("target");
    Files.createFile(target);
    Files.setPosixFilePermissions(target, Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE));
    Path ready = root.resolve("ready");
    Files.createSymbolicLink(ready, target.getFileName());
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    paths.datadir = root.resolve("instance");
    paths.runtimeRoot = root.resolve("runtimeRoot");
    paths.ready = ready;
    RiverDaemonFileSystem filesystem = new ApfsRiverDaemonFileSystem();
    assertNotEquals(StatusCode.OK, RiverDaemonPaths.verify(filesystem, paths));
  }
}
