package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SecurityAuditTelemetryTest {
  @Test
  void countsAuthenticationStatementDenialBytesAndOneForceCohorts(@TempDir Path root) {
    MemoryFile file = new MemoryFile();
    SecurityAuditLog audit = new SecurityAuditLog(openDirectory(root), file, 8);
    RemoteSessionAuthorizer authorizer = new RemoteSessionAuthorizer(
        7, SessionPermissions.READ, audit);

    assertEquals(StatusCode.OK, authorizer.auditAuthentication(true));
    assertEquals(StatusCode.OK, authorizer.authorize(SessionPermissions.READ));
    assertEquals(StatusCode.ACCESS_DENIED, authorizer.authorize(SessionPermissions.WRITE));

    SecurityAuditSnapshot snapshot = audit.snapshot();
    assertEquals(3, snapshot.decisions());
    assertEquals(120, snapshot.appendedBytes());
    assertEquals(3, snapshot.batches());
    assertEquals(3, snapshot.forceCalls());
    assertEquals(3, snapshot.cohortHistogram()[0]);
    assertEquals(3, snapshot.durableFrontier());
    assertEquals(StatusCode.OK, audit.close());
  }

  @Test
  void countsCapacityRejectionWithoutCreatingASequence(@TempDir Path root) {
    MemoryFile file = new MemoryFile();
    SecurityAuditLog audit = new SecurityAuditLog(openDirectory(root), file, 1);

    assertEquals(StatusCode.OK, audit.append(7, SecurityAuditLog.AUTHENTICATION, true));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        audit.append(7, SessionPermissions.READ, true));

    SecurityAuditSnapshot snapshot = audit.snapshot();
    assertEquals(1, snapshot.decisions());
    assertEquals(1, snapshot.capacityRejections());
    assertEquals(1, snapshot.durableFrontier());
    assertEquals(1, snapshot.forceCalls());
    assertEquals(StatusCode.OK, audit.close());
  }

  @Test
  void failedForceDoesNotAdvanceDurableFrontier(@TempDir Path root) {
    MemoryFile file = new MemoryFile();
    file.failForce = true;
    SecurityAuditLog audit = new SecurityAuditLog(openDirectory(root), file, 1);

    assertEquals(StatusCode.IO_FAILURE, audit.append(7, SecurityAuditLog.AUTHENTICATION, true));

    SecurityAuditSnapshot snapshot = audit.snapshot();
    assertEquals(0, snapshot.decisions());
    assertEquals(0, snapshot.durableFrontier());
    assertEquals(1, snapshot.forceCalls());
    assertEquals(1, snapshot.batches());
    assertEquals(40, snapshot.appendedBytes());
    assertEquals(0, snapshot.cohortHistogram()[0]);
    assertEquals(StatusCode.OK, audit.close());
  }

  @Test
  void reopenPreservesFrontierButStartsActivityCountersAtZero(@TempDir Path root)
      throws IOException {
    Files.createDirectories(root);
    SecurityAuditOpenResult firstResult = new SecurityAuditOpenResult();
    assertEquals(StatusCode.OK, SecurityAuditLog.open(root, 8, firstResult));
    SecurityAuditLog first = firstResult.audit();
    assertEquals(StatusCode.OK, first.append(7, SecurityAuditLog.AUTHENTICATION, true));
    assertEquals(StatusCode.OK, first.close());

    SecurityAuditOpenResult reopenedResult = new SecurityAuditOpenResult();
    assertEquals(StatusCode.OK, SecurityAuditLog.open(root, 8, reopenedResult));
    SecurityAuditLog reopened = reopenedResult.audit();
    SecurityAuditSnapshot afterOpen = reopened.snapshot();
    assertEquals(1, afterOpen.durableFrontier());
    assertEquals(0, afterOpen.decisions());
    assertEquals(0, afterOpen.appendedBytes());
    assertEquals(0, afterOpen.batches());
    assertEquals(0, afterOpen.forceCalls());
    assertEquals(0, afterOpen.cohortHistogram()[0]);

    assertEquals(StatusCode.OK, reopened.append(7, SessionPermissions.READ, true));
    SecurityAuditSnapshot afterNewActivity = reopened.snapshot();
    assertEquals(1, afterNewActivity.decisions());
    assertEquals(40, afterNewActivity.appendedBytes());
    assertEquals(2, afterNewActivity.durableFrontier());
    assertEquals(StatusCode.OK, reopened.close());
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            opened));
    return opened.directory();
  }

  private static final class MemoryFile implements DurableFile {
    private byte[] bytes = new byte[128];
    private int size;
    private boolean failForce;

    @Override
    public StatusCode read(long position, ByteBuffer target, IoResult result) {
      if (position < 0 || position > size) return StatusCode.INVALID_EXTERNAL_INPUT;
      int count = Math.min(target.remaining(), size - (int) position);
      target.put(bytes, (int) position, count);
      result.setBytesTransferred(count);
      return StatusCode.OK;
    }

    @Override
    public StatusCode write(long position, ByteBuffer source, IoResult result) {
      if (position < 0 || position > Integer.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int offset = (int) position;
      int count = source.remaining();
      ensureCapacity(offset + count);
      source.get(bytes, offset, count);
      size = Math.max(size, offset + count);
      result.setBytesTransferred(count);
      return StatusCode.OK;
    }

    @Override
    public StatusCode force(ForceMode mode) {
      return failForce ? StatusCode.IO_FAILURE : StatusCode.OK;
    }

    @Override
    public StatusCode truncate(long sizeBytes) {
      if (sizeBytes < 0 || sizeBytes > Integer.MAX_VALUE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      size = (int) sizeBytes;
      return StatusCode.OK;
    }

    @Override
    public StatusCode size(FileSizeResult result) {
      result.setSizeBytes(size);
      return StatusCode.OK;
    }

    @Override
    public StatusCode close() { return StatusCode.OK; }

    private void ensureCapacity(int required) {
      if (required <= bytes.length) return;
      byte[] expanded = new byte[Math.max(required, bytes.length * 2)];
      System.arraycopy(bytes, 0, expanded, 0, size);
      bytes = expanded;
    }
  }
}
