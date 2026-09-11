package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverFileResult;

/** Server-side owner of accepted stop controls. */
final class RiverDaemonStopControl {
  private final RiverDaemonFileSystem filesystem;
  private final RiverDaemonIdentity.IdentityResult identity;
  private final RiverDaemonRuntimeRecords.Metadata metadata;
  private final RiverFileResult probeResult = new RiverFileResult();
  private String acceptedNonce;
  private FileIdentity acceptedIdentity;

  RiverDaemonStopControl(RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity,
      RiverDaemonRuntimeRecords.Metadata metadata) {
    this.filesystem = filesystem;
    this.identity = identity;
    this.metadata = metadata;
  }

  /** Returns CANCELLED only after the request is atomically accepted and forced. */
  synchronized StatusCode poll() {
    if (filesystem == null || identity == null || metadata == null
        || identity.directory() == null || identity.lock() == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (acceptedNonce != null) {
      StatusCode forced = RiverDaemonRuntimeStorage.force(identity.directory());
      return forced.isOk() ? StatusCode.CANCELLED : forced;
    }
    RiverDaemonStopRecordReader.Result probe = RiverDaemonStopDirectory.probeRequest(
        identity.directory(), probeResult);
    if (!probe.status().isOk()) return probe.status();
    if (probe.entry() == null) return StatusCode.OK;
    StatusCode status = validate(probe.entry().record);
    if (status != StatusCode.OK) return status;
    String accepted = RiverDaemonStopRequest.ACCEPTED_PREFIX
        + probe.entry().record.requestNonce;
    status = RiverDaemonStopRecords.publishAccepted(identity.directory(), probe.entry().name, accepted);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    acceptedNonce = probe.entry().record.requestNonce;
    acceptedIdentity = probe.entry().identity;
    status = RiverDaemonRuntimeStorage.force(identity.directory());
    if (!status.isOk()) return status;
    return StatusCode.CANCELLED;
  }

  /** Removes the accepted receipt after runtime cleanup. */
  synchronized StatusCode cleanup() {
    if (identity == null || identity.directory() == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (acceptedNonce == null) return StatusCode.OK;
    StatusCode status = RiverDaemonStopRecords.removeIfOwned(identity.directory(),
        RiverDaemonStopRequest.ACCEPTED_PREFIX + acceptedNonce, acceptedIdentity);
    if (status.isOk()) {
      acceptedNonce = null;
      acceptedIdentity = null;
    }
    return status;
  }

  private StatusCode validate(RiverDaemonStopRequest.Record record) {
    if (record == null || metadata == null || identity == null) return StatusCode.CORRUPTION;
    if (record.high != metadata.incarnation.high() || record.low != metadata.incarnation.low()
        || !record.ownerNonce.equals(metadata.owner.nonce)) return StatusCode.NOT_OWNER;
    RiverFileResult result = new RiverFileResult();
    StatusCode status = RiverDaemonRuntimeStorage.openRuntime(
        filesystem, metadata.runtimeRoot, metadata.datadir, result);
    if (status != StatusCode.OK) return status == StatusCode.CONFLICT
        ? StatusCode.NOT_OWNER : status;
    io.riverdb.platform.riverd.RiverFile file = result.file();
    RiverDaemonRuntimeModel.ReadResult read = RiverDaemonRuntimeStorage.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return read.status;
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) return closeStatus;
    RiverDaemonRuntimeModel.RuntimeRecord runtime = RiverDaemonRuntimeCodec.parseRuntime(read.bytes);
    java.util.Arrays.fill(read.bytes, (byte) 0);
    if (runtime == null || !runtime.matches(metadata.datadir, metadata.incarnation, metadata.owner)
        || !record.runtimeChecksum.equals(runtime.checksum)) return StatusCode.NOT_OWNER;
    return StatusCode.OK;
  }
}
