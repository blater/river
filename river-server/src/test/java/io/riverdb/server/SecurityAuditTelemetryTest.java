package io.riverdb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.api.SessionAuthorizationPhase;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.testsupport.SecurityAuditTestOwner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SecurityAuditTelemetryTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x54454c454d455452L, 0x5941554449543031L);
  private static final long ACTIVE_BYTES = 64L + 108L * 8L;
  private static final long PENDING_BYTES = 256L * 4L;

  @Test
  void countsAuthenticationStatementDenialBytesAndOneForceCohorts(@TempDir Path root)
      throws Exception {
    SecurityAuditLog audit = SecurityAuditTestOwner.create(
        root, DATABASE, 1, ACTIVE_BYTES, PENDING_BYTES);
    CredentialValidityFence fence = validityFence();
    RemoteSessionAuthorizer authorizer = new RemoteSessionAuthorizer(
        7, SessionPermissions.READ, audit, fence);
    authorizer.bindRequest(1, 1, 1, CancellationToken.NONE, 0);
    assertEquals(StatusCode.OK, authorizer.auditAuthentication(StatusCode.OK));
    assertEquals(StatusCode.OK, authorizer.authorize(
        SessionPermissions.READ, SessionAuthorizationPhase.EXECUTE, 0));
    assertEquals(StatusCode.ACCESS_DENIED, authorizer.authorize(
        SessionPermissions.WRITE, SessionAuthorizationPhase.EXECUTE, 0));

    SecurityAuditSnapshot snapshot = audit.snapshot();
    assertEquals(3, snapshot.decisions());
    assertEquals(3L * 108L, snapshot.appendedBytes());
    assertEquals(3, snapshot.batches());
    assertEquals(3, snapshot.forceCalls());
    assertEquals(3, snapshot.cohortHistogram()[0]);
    assertEquals(3, snapshot.durableFrontier());
    assertEquals(StatusCode.OK, audit.finishClose());
    assertEquals(StatusCode.OK, fence.close());
  }

  @Test
  void rejectsActiveCapacityBeforeAssigningAnotherSequence(@TempDir Path root)
      throws Exception {
    SecurityAuditLog audit = SecurityAuditTestOwner.create(
        root, DATABASE, 1, 64L + 108L * 2L, PENDING_BYTES);
    try {
      assertEquals(StatusCode.OK, audit.append(7, SecurityAuditLog.AUTHENTICATION_DECISION,
          1, 1, 1, 0, 0, 0, true, StatusCode.OK, CancellationToken.NONE, 0));
      assertEquals(StatusCode.OK, audit.append(7,
          SecurityAuditLog.STATEMENT_ADMISSION_DECISION, 1, 1, 2,
          SessionAuthorizationPhase.EXECUTE, 0, SessionPermissions.READ, true,
          StatusCode.OK, CancellationToken.NONE, 0));
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, audit.append(7,
          SecurityAuditLog.STATEMENT_ADMISSION_DECISION, 1, 1, 3,
          SessionAuthorizationPhase.EXECUTE, 0, SessionPermissions.READ, true,
          StatusCode.OK, CancellationToken.NONE, 0));
      SecurityAuditSnapshot snapshot = audit.snapshot();
      assertEquals(2, snapshot.decisions());
      assertEquals(1, snapshot.capacityRejections());
      assertEquals(2, snapshot.durableFrontier());
    } finally {
      assertTrue(audit.finishClose().isOk());
    }
  }

  @Test
  void reopenPreservesFrontierButStartsActivityCountersAtZero(@TempDir Path root)
      throws Exception {
    SecurityAuditLog first = SecurityAuditTestOwner.create(
        root, DATABASE, 1, ACTIVE_BYTES, PENDING_BYTES);
    assertEquals(StatusCode.OK, first.append(7, SecurityAuditLog.AUTHENTICATION_DECISION,
        1, 1, 1, 0, 0, 0, true, StatusCode.OK, CancellationToken.NONE, 0));
    assertEquals(StatusCode.OK, first.finishClose());

    SecurityAuditLog reopened = SecurityAuditTestOwner.reopen(
        root, DATABASE, 1, ACTIVE_BYTES, PENDING_BYTES);
    SecurityAuditSnapshot afterOpen = reopened.snapshot();
    assertEquals(1, afterOpen.durableFrontier());
    assertEquals(0, afterOpen.decisions());
    assertEquals(0, afterOpen.appendedBytes());
    assertEquals(0, afterOpen.batches());
    assertEquals(0, afterOpen.forceCalls());
    assertEquals(0, afterOpen.cohortHistogram()[0]);
    assertEquals(StatusCode.OK, reopened.finishClose());
  }

  private static CredentialValidityFence validityFence() {
    CredentialValidityFenceOpenResult opened = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    if (CredentialValidityFence.create(now - 300_000L, now + 86_400_000L, opened)
        != StatusCode.OK) {
      throw new AssertionError("test credential validity bounds");
    }
    return opened.fence();
  }

}
