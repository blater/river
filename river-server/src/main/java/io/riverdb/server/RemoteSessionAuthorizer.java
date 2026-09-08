package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.api.SessionPermissions;

/** Connection principal permission policy with credential validity fencing. */
final class RemoteSessionAuthorizer implements SessionAuthorizer {
  private final int permissions;
  private final CredentialValidityFence validityFence;
  private long denials;

  RemoteSessionAuthorizer(
      int grantedPermissions,
      CredentialValidityFence credentialValidityFence) {
    permissions = grantedPermissions;
    validityFence = credentialValidityFence;
  }

  @Override
  public StatusCode authorize(int requiredPermission) {
    if (!SessionPermissions.valid(requiredPermission)
        || Integer.bitCount(requiredPermission) != 1) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode validity = validityFence.checkNow();
    boolean allowed = validity.isOk()
        && (permissions & requiredPermission) == requiredPermission;
    if (!allowed) {
      denials++;
      return StatusCode.ACCESS_DENIED;
    }
    return StatusCode.OK;
  }

  long denials() {
    return denials;
  }
}
