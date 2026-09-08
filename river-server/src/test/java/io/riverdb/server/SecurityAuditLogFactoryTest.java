package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

final class SecurityAuditLogFactoryTest {
  @Test
  void failedReopenClosesPassedDirectoryWithoutCreatingAuthority() {
    MissingDirectory directory = new MissingDirectory();
    SecurityAuditOpenResult result = new SecurityAuditOpenResult();
    assertEquals(StatusCode.CORRUPTION, SecurityAuditLogFactory.open(
        directory, DatabaseIncarnation.of(1, 2), 1,
        SecurityAuditLogFactory.DEFAULT_ACTIVE_MAXIMUM_BYTES,
        SecurityAuditLogFactory.DEFAULT_PENDING_MAXIMUM_BYTES, result));
    assertTrue(directory.closed);
    assertEquals(0, directory.createCalls);
    assertEquals(null, result.audit());
  }

  @Test
  void nearFullReopenRejectsBeforeReadinessAndClosesOwner(@TempDir Path root)
      throws Exception {
    long activeBytes = 64L + 108L * 2L;
    SecurityAuditLog first = io.riverdb.testsupport.SecurityAuditTestOwner.create(
        root, DatabaseIncarnation.of(1, 2), 1, activeBytes, 1_024);
    assertEquals(StatusCode.OK, first.append(7, SecurityAuditLog.AUTHENTICATION_DECISION,
        1, 1, 1, 0, 0, 0, true, StatusCode.OK, CancellationToken.NONE, 0));
    assertEquals(StatusCode.OK, first.append(7, SecurityAuditLog.STATEMENT_ADMISSION_DECISION,
        1, 1, 2, 1, 0, 1, true, StatusCode.OK, CancellationToken.NONE, 0));
    assertEquals(StatusCode.OK, first.finishClose());
    java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(
        java.io.IOException.class, () -> io.riverdb.testsupport.SecurityAuditTestOwner.reopen(
            root, DatabaseIncarnation.of(1, 2), 1, activeBytes, 1_024));
    assertTrue(failure.getMessage().contains("RESOURCE_EXHAUSTED"));
  }

  private static final class MissingDirectory implements RiverDirectory {
    boolean closed;
    int createCalls;

    @Override public FileIdentity identity() { return new FileIdentity(1, 2, 3); }
    @Override public StatusCode createDirectory(String n, RiverDirectoryResult r) {
      return StatusCode.CONFLICT;
    }
    @Override public StatusCode openDirectory(String n, RiverDirectoryResult r) {
      return StatusCode.CONFLICT;
    }
    @Override public StatusCode openFile(String n, RiverOpenMode m, RiverFileResult r) {
      return StatusCode.CONFLICT;
    }
    @Override public StatusCode createFile(String n, RiverFileResult r) {
      createCalls++;
      return StatusCode.CONFLICT;
    }
    @Override public StatusCode list(DirectoryListResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode publishExclusive(RiverFile f, String a, String b,
        DirectoryOperationResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode publishReplacement(RiverFile f, String a, String b,
        DirectoryOperationResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode publishDirectoryExclusive(RiverDirectory p, RiverDirectory d,
        String a, String b, DirectoryOperationResult r) { return StatusCode.CONFLICT; }
    @Override public StatusCode removeOwned(String n, FileIdentity i, DirectoryOperationResult r) {
      return StatusCode.CONFLICT;
    }
    @Override public StatusCode force(DirectoryOperationResult r) { return StatusCode.OK; }
    @Override public StatusCode close() { closed = true; return StatusCode.OK; }
  }
}
