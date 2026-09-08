package io.riverdb.server;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.SessionAuthorizationPhase;
import io.riverdb.engine.api.SessionPermissions;

/** Connection principal policy with audit-before-admission ordering. */
final class RemoteSessionAuthorizer implements SessionAuthorizer {
  private final long principalId;
  private final int permissions;
  private final SecurityAuditLog audit;
  private long denials;
  private long connectionCorrelation;
  private long sessionCorrelation;
  private long requestCorrelation;
  private CancellationToken cancellation = CancellationToken.NONE;
  private long deadlineNanos;

  RemoteSessionAuthorizer(
      long authenticatedPrincipalId,
      int grantedPermissions,
      SecurityAuditLog securityAudit) {
    principalId = authenticatedPrincipalId;
    permissions = grantedPermissions;
    audit = securityAudit;
  }

  @Override
  public StatusCode authorize(int requiredPermission, int phase, int programStep) {
    if (!SessionPermissions.valid(requiredPermission)
        || Integer.bitCount(requiredPermission) != 1
        || !SessionAuthorizationPhase.valid(phase)
        || programStep < 0
        || (programStep == 0 && phase == SessionAuthorizationPhase.PROGRAM_STEP)
        || (programStep > 0 && phase != SessionAuthorizationPhase.PROGRAM_STEP)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    boolean allowed = (permissions & requiredPermission) == requiredPermission;
    StatusCode decision = allowed ? StatusCode.OK : StatusCode.ACCESS_DENIED;
    StatusCode status = audit == null
        ? StatusCode.OK
        : audit.append(principalId, SecurityAuditLog.STATEMENT_ADMISSION_DECISION,
            connectionCorrelation, sessionCorrelation, requestCorrelation,
            phase, programStep, requiredPermission, allowed, decision,
            cancellation, deadlineNanos);
    if (!status.isOk()) {
      return status;
    }
    if (!allowed) {
      denials++;
      return StatusCode.ACCESS_DENIED;
    }
    return StatusCode.OK;
  }

  StatusCode auditAuthentication(StatusCode authenticationStatus) {
    boolean allowed = authenticationStatus.isOk();
    return audit == null
        ? StatusCode.OK
        : audit.append(principalId, SecurityAuditLog.AUTHENTICATION_DECISION,
            connectionCorrelation, sessionCorrelation, requestCorrelation,
            0, 0, 0, allowed, authenticationStatus,
            cancellation, deadlineNanos);
  }

  void bindRequest(long connection, long session, long request,
      CancellationToken token, long deadline) {
    connectionCorrelation = connection;
    sessionCorrelation = session;
    requestCorrelation = request;
    cancellation = token == null ? CancellationToken.NONE : token;
    deadlineNanos = deadline;
  }

  void clearRequest() {
    connectionCorrelation = 0;
    sessionCorrelation = 0;
    requestCorrelation = 0;
    cancellation = CancellationToken.NONE;
    deadlineNanos = 0;
  }

  void cancelActiveRequest() {
    if (audit != null) audit.cancelRequest(
        connectionCorrelation, sessionCorrelation, requestCorrelation);
  }

  void cancelConnection(long connection) {
    if (audit != null) audit.cancelConnection(connection);
  }

  long denials() {
    return denials;
  }
}
