package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.FileIdentity;
import java.security.SecureRandom;
import java.util.HexFormat;

final class RiverDaemonStopClient {
  private static final SecureRandom NONCES = new SecureRandom();

  private RiverDaemonStopClient() {
  }

  static StatusCode request(RiverDaemonTarget target, long timeoutMillis) {
    if (target == null || timeoutMillis <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    RequestState state = new RequestState(target, timeoutMillis);
    StatusCode primary = state.run();
    StatusCode cleanup = state.cleanup();
    return cleanup.isOk() ? primary : cleanup;
  }

  private static final class RequestState {
    final RiverDaemonTarget target;
    final long deadline;
    final String nonce = nonce();
    final String stageName = RiverDaemonStopRequest.STAGE_PREFIX + nonce
        + RiverDaemonStopRequest.STAGE_SUFFIX;
    FileIdentity ownedIdentity;
    boolean published;
    boolean joined;

    RequestState(RiverDaemonTarget target, long timeoutMillis) {
      this.target = target;
      deadline = RiverDaemonStopCompletion.deadline(timeoutMillis);
    }

    StatusCode run() {
      while (!RiverDaemonStopCompletion.expired(deadline)) {
        RiverDaemonStopDirectory.Scan scan = RiverDaemonStopDirectory.scan(target.directory, false);
        if (scan.status == StatusCode.RETRY) {
          if (!RiverDaemonStopCompletion.sleepPoll()) return StatusCode.CANCELLED;
          continue;
        }
        if (!scan.status.isOk()) return scan.status;
        if (scan.acceptedCount > 1) return StatusCode.CORRUPTION;
        if (scan.accepted != null) {
          if (scan.request != null) {
            StatusCode requestStatus = validateRequest(scan.request.record, target);
            if (!requestStatus.isOk()) return StatusCode.CORRUPTION;
            if (published && ownedIdentity != null
                && ownedIdentity.equals(scan.request.identity)) {
              StatusCode remove = RiverDaemonStopRecords.removeIfOwned(target.directory,
                  RiverDaemonStopRequest.REQUEST_NAME, scan.request.identity);
              if (!remove.isOk()) return remove;
            }
          }
          StatusCode status = validateAccepted(scan.accepted, target);
          if (!status.isOk()) return status;
          return RiverDaemonStopCompletion.await(target, deadline);
        }
        if (scan.request != null) {
          StatusCode status = validateRequest(scan.request.record, target);
          if (!status.isOk()) return status;
          joined = true;
          if (published && scan.request.record.requestNonce.equals(nonce)) {
            ownedIdentity = scan.request.identity;
          }
          status = target.revalidate(false);
          if (status == StatusCode.NOT_OWNER) {
            status = cleanup();
            if (!status.isOk()) return status;
            status = RiverDaemonStopCompletion.ensureClean(target);
            return status == StatusCode.TIMEOUT ? StatusCode.NOT_OWNER : status;
          }
          if (!status.isOk()) return status;
          if (!RiverDaemonStopCompletion.sleepPoll()) return StatusCode.CANCELLED;
          continue;
        }
        if (joined) return RiverDaemonStopCompletion.await(target, deadline);
        if (ownedIdentity == null) {
          StatusCode status = target.revalidate(true);
          if (!status.isOk()) return status;
          RiverDaemonStopRecords.Stage stage = RiverDaemonStopRecords.createStage(target, stageName);
          if (stage.status == StatusCode.CONFLICT) continue;
          if (!stage.status.isOk()) return stage.status;
          ownedIdentity = stage.identity;
        }
        StatusCode status = target.revalidate(true);
        if (!status.isOk()) return status;
        status = target.lockHeld();
        if (status != StatusCode.OK) return status;
        status = RiverDaemonStopRecords.publish(target.directory, stageName);
        if (status == StatusCode.OK) {
          published = true;
          joined = true;
        } else if (status != StatusCode.CONFLICT) {
          return status;
        }
        if (!RiverDaemonStopCompletion.sleepPoll()) return StatusCode.CANCELLED;
      }
      return StatusCode.TIMEOUT;
    }

    StatusCode cleanup() {
      if (ownedIdentity == null) return StatusCode.OK;
      StatusCode stage = RiverDaemonStopRecords.removeIfOwned(
          target.directory, stageName, ownedIdentity);
      StatusCode request = RiverDaemonStopRecords.removeIfOwned(
          target.directory, RiverDaemonStopRequest.REQUEST_NAME, ownedIdentity);
      return stage.isOk() ? request : stage;
    }
  }

  private static StatusCode validateRequest(RiverDaemonStopRequest.Record record,
      RiverDaemonTarget target) {
    if (record == null) return StatusCode.CORRUPTION;
    if (record.high != target.owner.high || record.low != target.owner.low
        || !record.ownerNonce.equals(target.owner.nonce)) return StatusCode.NOT_OWNER;
    return record.runtimeChecksum.equals(target.runtimeChecksum)
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private static StatusCode validateAccepted(RiverDaemonStopDirectory.ControlEntry entry,
      RiverDaemonTarget target) {
    if (entry == null || entry.record == null) return StatusCode.CORRUPTION;
    if (target != null && (entry.record.high != target.owner.high
        || entry.record.low != target.owner.low
        || !entry.record.ownerNonce.equals(target.owner.nonce))) return StatusCode.CORRUPTION;
    if (target != null && target.runtimeChecksum != null
        && !entry.record.runtimeChecksum.equals(target.runtimeChecksum)) return StatusCode.CORRUPTION;
    return StatusCode.OK;
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    NONCES.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

}
