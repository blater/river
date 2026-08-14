package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.SessionPermissions;

/** Connection principal policy with audit-before-admission ordering. */
final class RemoteSessionAuthorizer implements SessionAuthorizer {
  private final long principalId;
  private final int permissions;
  private final SecurityAuditLog audit;
  private long denials;

  RemoteSessionAuthorizer(
      long authenticatedPrincipalId,
      int grantedPermissions,
      SecurityAuditLog securityAudit) {
    principalId = authenticatedPrincipalId;
    permissions = grantedPermissions;
    audit = securityAudit;
  }

  @Override
  public StatusCode authorize(int requiredPermission) {
    if (!SessionPermissions.valid(requiredPermission)
        || Integer.bitCount(requiredPermission) != 1) {
      return StatusCode.INVARIANT_BROKEN;
    }
    boolean allowed = (permissions & requiredPermission) == requiredPermission;
    StatusCode status = audit == null
        ? StatusCode.OK
        : audit.append(principalId, requiredPermission, allowed);
    if (!status.isOk()) {
      return status;
    }
    if (!allowed) {
      denials++;
      return StatusCode.ACCESS_DENIED;
    }
    return StatusCode.OK;
  }

  StatusCode auditAuthentication(boolean allowed) {
    return audit == null
        ? StatusCode.OK
        : audit.append(principalId, SecurityAuditLog.AUTHENTICATION, allowed);
  }

  long denials() {
    return denials;
  }
}
