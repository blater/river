package io.riverdb.platform.riverd.apfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.FileIdentity;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
final class ApfsRiverDaemonFileSystemTest {
  private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE);

  @Test
  void opensOwnerOnlyDirectoryAndListsThroughStableDescriptor(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    RiverFileResult fileResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile("record", RiverOpenMode.CREATE_NEW, fileResult));
    RiverFile file = fileResult.file();
    assertEquals(StatusCode.OK, file.write(0, ByteBuffer.wrap(new byte[] {1, 2, 3}), new IoResult()));
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    DirectoryListResult list = new DirectoryListResult(2);
    assertEquals(StatusCode.OK, directory.list(list));
    assertEquals(1, list.size());
    assertEquals("record", list.name(0));
    assertEquals(StatusCode.OK, directory.list(list));
    assertEquals(1, list.size());
    assertEquals("record", list.name(0));
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void roundTripsSlicedReadOnlyBuffersAndShortReads(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    RiverFileResult fileResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("offsets", fileResult));
    RiverFile file = fileResult.file();

    ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 10, 11, 12, 98})
        .asReadOnlyBuffer();
    source.position(1);
    source.limit(4);
    ByteBuffer sliced = source.slice();
    IoResult write = new IoResult();
    assertEquals(StatusCode.OK, file.write(5, sliced, write));
    assertEquals(3, write.bytesTransferred());
    assertEquals(3, sliced.position());

    ByteBuffer destination = ByteBuffer.allocate(5);
    destination.position(1);
    IoResult read = new IoResult();
    assertEquals(StatusCode.OK, file.read(5, destination, read));
    assertEquals(3, read.bytesTransferred());
    assertEquals(4, destination.position());
    assertEquals(10, destination.get(1));
    assertEquals(11, destination.get(2));
    assertEquals(12, destination.get(3));

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
  void createsPrivateTreeBelowVerifiedNonPrivateAncestor(@TempDir Path root) throws Exception {
    Path ancestor = root.resolve("home");
    Files.createDirectory(ancestor);
    Files.setPosixFilePermissions(ancestor, Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_EXECUTE));
    RiverDirectory parent = openAncestor(ancestor);
    RiverDirectoryResult childResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, parent.createDirectory("private", childResult));
    RiverDirectory child = childResult.directory();
    assertEquals(StatusCode.OK, child.close());
    assertEquals(StatusCode.OK, parent.close());
  }

  @Test
  void rejectsRootAliasAndCompetingLocks(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    Path alias = root.resolveSibling(root.getFileName() + "-alias");
    Files.createSymbolicLink(alias, root);
    RiverDaemonFileSystem fileSystem = new ApfsRiverDaemonFileSystem();
    RiverDirectoryResult rejected = new RiverDirectoryResult();
    assertNotEquals(StatusCode.OK, fileSystem.openDirectory(alias, rejected));

    RiverDirectory directory = open(root);
    RiverFileResult firstResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile("instance.lock", RiverOpenMode.CREATE_NEW, firstResult));
    RiverFile first = firstResult.file();
    RiverLockResult firstLock = new RiverLockResult();
    assertEquals(StatusCode.OK, fileSystem.acquireExclusive(first, firstLock));

    RiverFileResult secondResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile("instance.lock", RiverOpenMode.EXISTING, secondResult));
    RiverLockResult secondLock = new RiverLockResult();
    assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(secondResult.file(), secondLock));
    assertEquals(StatusCode.OK, first.close());
    RiverLockResult stillHeld = new RiverLockResult();
    assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(secondResult.file(), stillHeld));
    assertEquals(StatusCode.OK, secondResult.file().close());
    assertEquals(StatusCode.OK, firstLock.lock().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void lockOwnsIndependentDescriptorAndRejectsDuplicateAcquire(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDaemonFileSystem fileSystem = new ApfsRiverDaemonFileSystem();
    RiverDirectory directory = open(root);
    RiverFileResult fileResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("instance.lock", fileResult));
    RiverFile file = fileResult.file();
    RiverLockResult lockResult = new RiverLockResult();
    assertEquals(StatusCode.OK, fileSystem.acquireExclusive(file, lockResult));
    RiverLockResult duplicate = new RiverLockResult();
    assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(file, duplicate));
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, lockResult.lock().close());

    RiverFileResult reopened = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile(
        "instance.lock", RiverOpenMode.EXISTING, reopened));
    RiverLockResult afterRelease = new RiverLockResult();
    assertEquals(StatusCode.OK, fileSystem.acquireExclusive(reopened.file(), afterRelease));
    assertEquals(StatusCode.OK, afterRelease.lock().close());
    assertEquals(StatusCode.OK, reopened.file().close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void acquiringAClosedFileIsRejected(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDaemonFileSystem fileSystem = new ApfsRiverDaemonFileSystem();
    RiverDirectory directory = open(root);
    RiverFileResult fileResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("closed", fileResult));
    RiverFile file = fileResult.file();
    assertEquals(StatusCode.OK, file.close());
    RiverLockResult lock = new RiverLockResult();
    assertEquals(StatusCode.CLOSED, fileSystem.acquireExclusive(file, lock));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void rejectsPreexistingHardlinkedRegularFiles(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    Path first = root.resolve("first");
    Files.write(first, new byte[] {1});
    Files.setPosixFilePermissions(first, Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE));
    Files.createLink(root.resolve("alias"), first);
    RiverDirectory directory = open(root);
    RiverFileResult result = new RiverFileResult();
    assertEquals(StatusCode.ACCESS_DENIED, directory.openFile(
        "first", RiverOpenMode.EXISTING, result));
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void refusedExistingOpenDoesNotRemoveThePreexistingFile(@TempDir Path root) throws Exception {
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
  void separatesExclusiveAndReplacementPublicationAndChecksCleanupIdentity(@TempDir Path root)
      throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    RiverFileResult firstResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile("stage-a", RiverOpenMode.CREATE_NEW, firstResult));
    RiverFile first = firstResult.file();
    assertEquals(StatusCode.OK, first.write(0, ByteBuffer.wrap(new byte[] {4}), new IoResult()));
    assertEquals(StatusCode.OK, first.force(ForceMode.CONTENT_AND_METADATA));
    DirectoryOperationResult publication = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.publishExclusive(first, "stage-a", "authority", publication));
    assertEquals(StatusCode.OK, directory.force(new DirectoryOperationResult()));
    assertEquals(StatusCode.CONFLICT, directory.publishExclusive(
        first, "stage-a", "authority", publication));

    RiverFileResult replacementResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile(
        "stage-b", RiverOpenMode.CREATE_NEW, replacementResult));
    RiverFile replacement = replacementResult.file();
    assertEquals(StatusCode.OK, replacement.write(
        0, ByteBuffer.wrap(new byte[] {9}), new IoResult()));
    assertEquals(StatusCode.OK, replacement.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, directory.publishReplacement(
        replacement, "stage-b", "authority", publication));
    assertEquals(StatusCode.OK, directory.force(new DirectoryOperationResult()));

    FileIdentity wrong = new FileIdentity(0, 0, 0);
    assertEquals(StatusCode.CONFLICT, directory.removeOwned("authority", wrong, publication));
    DirectoryListResult list = new DirectoryListResult(4);
    assertEquals(StatusCode.OK, directory.list(list));
    FileIdentity authority = null;
    RiverFileResult authorityResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile("authority", RiverOpenMode.EXISTING, authorityResult));
    authority = authorityResult.file().identity();
    assertEquals(StatusCode.OK, authorityResult.file().close());
    assertEquals(StatusCode.OK, directory.removeOwned("authority", authority, publication));
    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, replacement.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void leavesInterruptedStageForRetryAndForceReportsDurability(@TempDir Path root) throws Exception {
    Files.setPosixFilePermissions(root, OWNER_DIRECTORY);
    RiverDirectory directory = open(root);
    RiverFileResult stageResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.openFile(".instance-nonce.stage", RiverOpenMode.CREATE_NEW,
        stageResult));
    RiverFile stage = stageResult.file();
    assertEquals(StatusCode.OK, stage.write(0, ByteBuffer.wrap(new byte[] {7, 7}), new IoResult()));
    assertEquals(StatusCode.OK, stage.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, directory.force(new DirectoryOperationResult()));
    assertEquals(StatusCode.OK, stage.close());
    assertEquals(StatusCode.OK, directory.close());

    RiverDirectory retry = open(root);
    RiverFileResult reopened = new RiverFileResult();
    assertEquals(StatusCode.OK, retry.openFile(
        ".instance-nonce.stage", RiverOpenMode.EXISTING, reopened));
    assertEquals(StatusCode.OK, reopened.file().close());
    assertEquals(StatusCode.OK, retry.close());
  }

  @Test
  void publishesStagedDirectoryAcrossParentsAndForcesBothNamespaces(@TempDir Path root)
      throws Exception {
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
    RiverDaemonFileSystem fileSystem = new ApfsRiverDaemonFileSystem();
    RiverDirectoryResult result = new RiverDirectoryResult();
    Path canonical = root.toRealPath();
    assertEquals(StatusCode.OK, fileSystem.openDirectory(canonical, result),
        () -> "path=" + canonical);
    return result.directory();
  }

  private static RiverDirectory openAncestor(Path root) throws Exception {
    RiverDaemonFileSystem fileSystem = new ApfsRiverDaemonFileSystem();
    RiverDirectoryResult result = new RiverDirectoryResult();
    Path canonical = root.toRealPath();
    assertEquals(StatusCode.OK, fileSystem.openAncestor(canonical, result),
        () -> "path=" + canonical);
    return result.directory();
  }
}
