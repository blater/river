package io.riverdb.platform.riverd.ntfs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
final class WindowsRiverDaemonFileSystemTest {
  @Test
  void createsPrivateRootBelowAncestorAndRoundTrips(@TempDir Path root) {
    RiverDaemonFileSystem fileSystem = new WindowsRiverDaemonFileSystem();
    RiverDirectoryResult ancestorResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, fileSystem.openAncestor(root, ancestorResult));
    RiverDirectory ancestor = ancestorResult.directory();
    RiverDirectoryResult privateResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, ancestor.createDirectory("private", privateResult));
    RiverDirectory directory = privateResult.directory();
    RiverFileResult fileResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("record", fileResult));
    RiverFile file = fileResult.file();
    ByteBuffer source = ByteBuffer.wrap(new byte[] {8, 9, 10}).asReadOnlyBuffer();
    IoResult io = new IoResult();
    assertEquals(StatusCode.OK, file.write(4, source, io));
    ByteBuffer target = ByteBuffer.allocate(4);
    assertEquals(StatusCode.OK, file.read(4, target, io));
    assertEquals(3, io.bytesTransferred());
    assertArrayEquals(new byte[] {8, 9, 10}, new byte[] {target.get(0), target.get(1), target.get(2)});
    FileSizeResult size = new FileSizeResult();
    assertEquals(StatusCode.OK, file.size(size));
    assertEquals(7, size.sizeBytes());
    assertEquals(StatusCode.OK, file.close());
    assertEquals(StatusCode.OK, directory.close());
    assertEquals(StatusCode.OK, ancestor.close());
  }

  @Test
  void locksAreIndependentAndSurviveFileClose(@TempDir Path root) {
    RiverDirectoryResult ancestorResult = new RiverDirectoryResult();
    RiverDaemonFileSystem fileSystem = new WindowsRiverDaemonFileSystem();
    assertEquals(StatusCode.OK, fileSystem.openAncestor(root, ancestorResult));
    RiverDirectoryResult privateResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, ancestorResult.directory().createDirectory("private", privateResult));
    RiverDirectory directory = privateResult.directory();
    RiverFileResult first = new RiverFileResult();
    RiverFileResult second = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("lock", first));
    assertEquals(StatusCode.OK, first.file().write(0, ByteBuffer.wrap(new byte[] {4, 5}), new IoResult()));
    assertEquals(StatusCode.OK, directory.openFile("lock", RiverOpenMode.EXISTING, second));
    RiverLockResult held = new RiverLockResult();
    assertEquals(StatusCode.OK, fileSystem.acquireExclusive(first.file(), held));
    ByteBuffer ownerRecord = ByteBuffer.allocate(2);
    assertEquals(StatusCode.OK, second.file().read(0, ownerRecord, new IoResult()));
    assertArrayEquals(new byte[] {4, 5}, ownerRecord.array());
    assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(second.file(), new RiverLockResult()));
    assertEquals(StatusCode.OK, first.file().close());
    assertEquals(StatusCode.CONFLICT, fileSystem.acquireExclusive(second.file(), new RiverLockResult()));
    assertEquals(StatusCode.OK, held.lock().close());
    assertEquals(StatusCode.OK, second.file().close());
    assertEquals(StatusCode.OK, directory.close());
    assertEquals(StatusCode.OK, ancestorResult.directory().close());
  }

  @Test
  void rejectsReparsePathAlias(@TempDir Path root) throws Exception {
    Path alias = root.resolveSibling(root.getFileName() + "-alias");
    Files.deleteIfExists(alias);
    Files.createSymbolicLink(alias, root);
    RiverDirectoryResult result = new RiverDirectoryResult();
    assertNotEquals(StatusCode.OK, new WindowsRiverDaemonFileSystem().openDirectory(alias, result));
    Files.deleteIfExists(alias);
  }

  @Test
  void listsRepeatedlyFromIndependentDirectoryHandle(@TempDir Path root) {
    RiverDirectoryResult ancestorResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, new WindowsRiverDaemonFileSystem().openAncestor(root, ancestorResult));
    RiverDirectoryResult privateResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, ancestorResult.directory().createDirectory("private", privateResult));
    RiverDirectory directory = privateResult.directory();
    RiverFileResult file = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("entry", file));
    DirectoryListResult list = new DirectoryListResult(4);
    assertEquals(StatusCode.OK, directory.list(list));
    assertEquals(1, list.size());
    assertEquals(StatusCode.OK, directory.list(list));
    assertEquals(1, list.size());
    assertEquals(StatusCode.OK, file.file().close());
    assertEquals(StatusCode.OK, directory.close());
    assertEquals(StatusCode.OK, ancestorResult.directory().close());
  }

  @Test
  void publishesFilesDirectoriesAndForcesNamespaces(@TempDir Path root) {
    RiverDirectoryResult ancestorResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, new WindowsRiverDaemonFileSystem().openAncestor(root, ancestorResult));
    RiverDirectory ancestor = ancestorResult.directory();
    RiverDirectoryResult privateResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, ancestor.createDirectory("private", privateResult));
    RiverDirectory directory = privateResult.directory();

    RiverFileResult firstResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("stage-a", firstResult));
    RiverFile first = firstResult.file();
    assertEquals(StatusCode.OK, first.write(0, ByteBuffer.wrap(new byte[] {1}), new IoResult()));
    assertEquals(StatusCode.OK, first.force(ForceMode.CONTENT_AND_METADATA));
    DirectoryOperationResult publication = new DirectoryOperationResult();
    assertEquals(StatusCode.OK, directory.publishExclusive(first, "stage-a", "authority", publication));
    assertEquals(StatusCode.OK, directory.force(publication));

    RiverFileResult replacementResult = new RiverFileResult();
    assertEquals(StatusCode.OK, directory.createFile("stage-b", replacementResult));
    RiverFile replacement = replacementResult.file();
    assertEquals(StatusCode.OK, replacement.write(0, ByteBuffer.wrap(new byte[] {2}), new IoResult()));
    assertEquals(StatusCode.OK, replacement.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, directory.publishReplacement(
        replacement, "stage-b", "authority", publication));
    assertEquals(StatusCode.OK, directory.force(publication));

    RiverDirectoryResult stagingResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, directory.createDirectory("staging", stagingResult));
    RiverDirectory staging = stagingResult.directory();
    RiverDirectoryResult stagedResult = new RiverDirectoryResult();
    assertEquals(StatusCode.OK, staging.createDirectory("database", stagedResult));
    RiverDirectory staged = stagedResult.directory();
    assertEquals(StatusCode.OK, directory.publishDirectoryExclusive(
        staging, staged, "database", "database", publication));

    assertEquals(StatusCode.OK, first.close());
    assertEquals(StatusCode.OK, replacement.close());
    assertEquals(StatusCode.OK, staged.close());
    assertEquals(StatusCode.OK, staging.close());
    assertEquals(StatusCode.OK, directory.close());
    assertEquals(StatusCode.OK, ancestor.close());
  }
}
