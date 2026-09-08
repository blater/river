package io.riverdb.platform.riverd.linux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverLockResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
final class LinuxRiverDaemonFileSystemTest {
  private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE);

  @Test
  void opensPrivateRootAndReportsStableIdentity(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    assertNotEquals(0, directory.identity().low());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void createsPrivateChildBelowVerifiedAncestor(@TempDir Path root) throws Exception {
    Path ancestor = root.resolve("ancestor");
    Files.createDirectory(ancestor);
    Files.setPosixFilePermissions(ancestor, Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE));
    RiverDirectory parent = openAncestor(ancestor);
    RiverDirectoryResult child = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, parent.createDirectory("private", child));
    assertEquals(StatusCode.OK, child.directory().close());
    assertEquals(StatusCode.OK, parent.close());
  }

  @Test
  void roundTripsOffsetAndShortReadThenTruncates(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    RiverFileResult opened = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("record", opened));
    RiverFile file = opened.file();
    ByteBuffer source = ByteBuffer.wrap(new byte[] {9, 10, 11, 12, 13}).asReadOnlyBuffer();
    source.position(1);
    source.limit(4);
    ByteBuffer slice = source.slice();
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, file.write(5, slice, io));
    assertEquals(3, io.bytesTransferred());
    ByteBuffer destination = ByteBuffer.allocate(5);
    destination.position(1);
    assertEquals(StatusCode.OK, file.read(5, destination, io));
    assertEquals(3, io.bytesTransferred());
    assertArrayEquals(new byte[] {10, 11, 12}, new byte[] {
        destination.get(1), destination.get(2), destination.get(3)});
    FileSizeResult size = new FileSizeResult();
    assertEquals(StatusCode.OK, file.size(size));
    assertEquals(8, size.sizeBytes());
    assertEquals(StatusCode.OK, file.truncate(6));
    assertEquals(StatusCode.OK, file.size(size));
    assertEquals(6, size.sizeBytes());
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void lockRemainsHeldAfterVerifiedFileCloses(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDaemonFileSystem fileSystem = new LinuxRiverDaemonFileSystem();
    RiverDirectory directory = open(root);
    RiverFileResult first = new RiverFileResult();
    RiverFileResult second = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("lock", first));
    assertEquals(StatusCode.OK, directory.openFile("lock", RiverOpenMode.EXISTING, second));
    RiverLockResult held = new RiverLockResult();
    assertEquals(StatusCode.OK, fileSystem.acquireExclusive(first.file(), held));
    assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(second.file(), new RiverLockResult()));
    assertEquals(StatusCode.OK, first.file().close());
    assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(second.file(), new RiverLockResult()));
    assertEquals(StatusCode.OK, held.lock().close());
    assertEquals(StatusCode.OK, second.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void publishesExclusivelyAndListsRepeatedly(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    RiverFileResult stage = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("stage", stage));
    DirectoryOperationResult publication = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.publishExclusive(
        stage.file(), "stage", "authority", publication));
    assertEquals(StatusCode.CONFLICT, directory.publishExclusive(
        stage.file(), "stage", "authority", publication));
    DirectoryListResult list = new DirectoryListResult(4);
    assertEquals(StatusCode.OK, directory.list(list));
    assertEquals(1, list.size());
    assertEquals(StatusCode.OK, directory.list(list));
    assertEquals(1, list.size());
    assertEquals(StatusCode.OK, stage.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void refusesHardlinkedRegularFile(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    Path original = root.resolve("original");
    Files.write(original, new byte[] {1});
    Files.setPosixFilePermissions(original, Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Files.createLink(root.resolve("alias"), original);
    RiverDirectory directory = open(root);
    RiverFileResult result = new RiverFileResult();
    assertEquals(StatusCode.ACCESS_DENIED, directory.openFile(
        "original", RiverOpenMode.EXISTING, result));
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(original));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsRootAlias(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    Path alias = root.resolveSibling(root.getFileName() + "-alias");
    Files.deleteIfExists(alias);
    Files.createSymbolicLink(alias, root);
    RiverDirectoryResult rejected = new RiverDirectoryResult();
    assertNotEquals(StatusCode.OK, new LinuxRiverDaemonFileSystem().openDirectory(alias, rejected));

    Files.deleteIfExists(alias);
  }

  @Test
  void processDeathReleasesLockAndPreservesForcedFile(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    Process child = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        LockProcess.class.getName(), root.toRealPath().toString())
        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try {
      CompletableFuture<String> readiness = new CompletableFuture<>();
      Thread.ofVirtual().start(() -> {
        try { readiness.complete(child.inputReader().readLine()); }
        catch (Exception failure) { readiness.completeExceptionally(failure); }
      });
      assertEquals("ready", readiness.get(15, TimeUnit.SECONDS));
      RiverDirectory directory = open(root);
      RiverFileResult file = new RiverFileResult();
      assertEquals(StatusCode.OK, directory.openFile("owner", RiverOpenMode.EXISTING, file));
      RiverDaemonFileSystem fileSystem = new LinuxRiverDaemonFileSystem();
      assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(file.file(), new RiverLockResult()));
      child.destroyForcibly();
      assertTrue(child.waitFor(15, TimeUnit.SECONDS));
      RiverLockResult lock = new RiverLockResult();
      assertEquals(StatusCode.OK, fileSystem.acquireExclusive(file.file(), lock));
      ByteBuffer bytes = ByteBuffer.allocate(3);
      assertEquals(StatusCode.OK, file.file().read(0, bytes, new IoResult()));
      assertArrayEquals(new byte[] {4, 5, 6}, bytes.array());
      assertEquals(StatusCode.OK, lock.lock().close());
      assertEquals(StatusCode.OK, file.file().close());
      assertEquals(StatusCode.OK, directory.close());
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(15, TimeUnit.SECONDS));
    }
  }

  public static final class LockProcess {
    public static void main(String[] args) throws Exception {
      RiverDirectory directory = open(Path.of(args[0]));
      RiverFileResult file = new RiverFileResult();
      assertEquals(StatusCode.OK, directory.createFile("owner", file));
      RiverLockResult lock = new RiverLockResult();
      assertEquals(StatusCode.OK, new LinuxRiverDaemonFileSystem().acquireExclusive(file.file(), lock));
      assertEquals(StatusCode.OK, file.file().write(0, ByteBuffer.wrap(new byte[] {4, 5, 6}), new IoResult()));
      assertEquals(StatusCode.OK, file.file().force(ForceMode.CONTENT_AND_METADATA));
      assertEquals(StatusCode.OK, directory.force(new DirectoryOperationResult()));
      System.out.println("ready");
      System.out.flush();
      System.in.read();
    }
  }

  @Test
  void refusedExistingOpenDoesNotRemovePreexistingFile(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    Path existing = root.resolve("existing");
    Files.write(existing, new byte[] {4, 5, 6});
    Files.setPosixFilePermissions(existing, Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ));
    RiverDirectory directory = open(root);
    RiverFileResult result = new RiverFileResult();
    assertEquals(StatusCode.ACCESS_DENIED, directory.openFile(
        "existing", RiverOpenMode.EXISTING, result));
    assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(existing));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void replacementValidatesTargetAndOwnedCleanup(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    RiverFileResult firstResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("stage-a", firstResult));
    RiverFile first = firstResult.file();
    assertEquals(StatusCode.OK, first.write(0, ByteBuffer.wrap(new byte[] {4}), new IoResult()));
    assertEquals(StatusCode.OK, first.force(ForceMode.CONTENT_AND_METADATA));
    DirectoryOperationResult publication = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.publishExclusive(first, "stage-a", "authority", publication));

    RiverFileResult replacementResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("stage-b", replacementResult));
    RiverFile replacement = replacementResult.file();
    assertEquals(StatusCode.OK, replacement.write(0, ByteBuffer.wrap(new byte[] {9}), new IoResult()));
    assertEquals(StatusCode.OK, replacement.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, directory.publishReplacement(
        replacement, "stage-b", "authority", publication));
    assertEquals(StatusCode.CONFLICT, directory.removeOwned(
        "authority", new io.riverdb.platform.riverd.FileIdentity(0, 0, 0), publication));
    RiverFileResult authority = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile("authority", RiverOpenMode.EXISTING, authority));
    assertEquals(StatusCode.OK, directory.removeOwned("authority", authority.file().identity(), publication));
    assertEquals(StatusCode.OK, authority.file().close());
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, replacement.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void publishesStagedDirectoryAcrossParents(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory parent = open(root);
    RiverDirectoryResult stagingResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, parent.createDirectory("staging", stagingResult));
    RiverDirectory staging = stagingResult.directory();
    RiverDirectoryResult childResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, staging.createDirectory("database", childResult));
    RiverDirectory child = childResult.directory();
    DirectoryOperationResult publication = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, parent.publishDirectoryExclusive(
        staging, child, "database", "database", publication));
    DirectoryListResult list = new DirectoryListResult(4);
    assertEquals(StatusCode.OK, parent.list(list));
    assertEquals(2, list.size());
    assertEquals(StatusCode.OK, child.close());
    assertEquals(StatusCode.OK, staging.close());
    assertEquals(StatusCode.OK, parent.close());
  }

  private static RiverDirectory open(Path root) throws Exception {
    RiverDirectoryResult result = new RiverDirectoryResult();
    Path canonical = root.toRealPath();
    assertEquals(StatusCode.OK, new LinuxRiverDaemonFileSystem().openDirectory(canonical, result),
        () -> "path=" + canonical);
    return result.directory();
  }

  private static RiverDirectory openAncestor(Path root) throws Exception {
    RiverDirectoryResult result = new RiverDirectoryResult();
    Path canonical = root.toRealPath();
    assertEquals(StatusCode.OK, new LinuxRiverDaemonFileSystem().openAncestor(canonical, result),
        () -> "path=" + canonical);
    return result.directory();
  }
}
