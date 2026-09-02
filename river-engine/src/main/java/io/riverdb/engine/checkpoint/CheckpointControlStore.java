package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableDirectory;

/** Atomic facade over the manifest and its immutable auxiliary generations. */
public final class CheckpointControlStore {
  public static final String FILE_NAME = CheckpointManifestStore.FILE_NAME;
  public static final int BYTES = CheckpointManifestFormat.BYTES;

  private final CheckpointManifestStore manifests = new CheckpointManifestStore();
  private final CheckpointVersionGenerationWriter writer =
      new CheckpointVersionGenerationWriter();
  private final CheckpointVersionGenerationReader reader =
      new CheckpointVersionGenerationReader();
  private final CheckpointVersionCleanup cleanup = new CheckpointVersionCleanup();
  private final CheckpointLogicalRowIdGenerationWriter logicalWriter =
      new CheckpointLogicalRowIdGenerationWriter();
  private final CheckpointLogicalRowIdGenerationReader logicalReader =
      new CheckpointLogicalRowIdGenerationReader();
  private final CheckpointLogicalRowIdCleanup logicalCleanup =
      new CheckpointLogicalRowIdCleanup();
  private final CheckpointState current = new CheckpointState();
  private final CheckpointManifestVersion currentVersion = new CheckpointManifestVersion();
  private final CheckpointLogicalRowIdManifestReference currentLogicalRowIds =
      new CheckpointLogicalRowIdManifestReference();
  private int targetSlot;

  public StatusCode read(DurableDirectory directory, CheckpointState result) {
    if (directory == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    CheckpointManifestVersion version = new CheckpointManifestVersion();
    CheckpointLogicalRowIdManifestReference logicalRowIds =
        new CheckpointLogicalRowIdManifestReference();
    StatusCode status = manifests.read(directory, result, version, logicalRowIds);
    if (!status.isOk()) return status;
    if (version.available()) {
      status = reader.open(directory, result, version);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
    }
    status = logicalReader.open(
        directory, result, logicalRowIds, result.logicalRowIdDirectory());
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    result.attachLoadedLogicalRowIds();
    finishCleanup(directory, result, version, logicalRowIds);
    return StatusCode.OK;
  }

  public StatusCode install(DurableDirectory directory, CheckpointState state) {
    if (directory == null || state == null || !state.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = admitGeneration(directory, state.checkpointId());
    if (!status.isOk()) return status;
    if (state.logicalRowIdSource() == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pages = 0;
    long bytes = 0;
    int versionSlot = -1;
    if (state.versionDirectoryRequired()) {
      status = writer.install(directory, state, targetSlot);
      if (!status.isOk()) return status;
      pages = writer.pageCount();
      bytes = writer.fileBytes();
      if (pages > 0) versionSlot = targetSlot;
    }
    CheckpointLogicalRowIdManifestReference logicalRowIds =
        new CheckpointLogicalRowIdManifestReference();
    status = logicalWriter.install(
        directory, state, state.logicalRowIdSource(), targetSlot,
        currentLogicalRowIds.slot(), logicalRowIds);
    if (!status.isOk()) return status;
    status = manifests.install(
        directory, state, pages, bytes, versionSlot, currentVersion.slot(), logicalRowIds);
    if (status.isOk()) {
      CheckpointManifestVersion published = new CheckpointManifestVersion();
      published.set(pages, bytes, versionSlot, currentVersion.slot());
      finishCleanup(directory, state, published, logicalRowIds);
    }
    return status;
  }

  private StatusCode admitGeneration(DurableDirectory directory, long checkpointId) {
    current.reset();
    StatusCode status = manifests.read(
        directory, current, currentVersion, currentLogicalRowIds);
    if (status == StatusCode.CONFLICT) {
      currentVersion.set(0, 0, -1, -1);
      currentLogicalRowIds.set(0, 0, 0, -1, -1);
      targetSlot = 0;
      status = cleanup.remove(directory, targetSlot);
      return status.isOk() ? logicalCleanup.remove(directory, targetSlot) : status;
    }
    if (!status.isOk()) return status;
    if (checkpointId <= current.checkpointId()) return StatusCode.CONFLICT;
    status = finishPendingCleanup(directory);
    if (!status.isOk()) return StatusCode.RETRY;
    targetSlot = currentLogicalRowIds.slot() == 0 ? 1 : 0;
    status = cleanup.remove(directory, targetSlot);
    if (status.isOk()) status = logicalCleanup.remove(directory, targetSlot);
    return status.isOk() ? StatusCode.OK : StatusCode.RETRY;
  }

  private StatusCode finishPendingCleanup(DurableDirectory directory) {
    int pending = currentVersion.cleanupSlot();
    int logicalPending = currentLogicalRowIds.cleanupSlot();
    if (pending < 0 && logicalPending < 0) return StatusCode.OK;
    StatusCode status = cleanup.remove(directory, pending);
    if (status.isOk()) status = logicalCleanup.remove(directory, logicalPending);
    if (!status.isOk()) return status;
    CheckpointLogicalRowIdManifestReference logical = new CheckpointLogicalRowIdManifestReference();
    logical.set(
        currentLogicalRowIds.count(), currentLogicalRowIds.fileBytes(),
        currentLogicalRowIds.digest(), currentLogicalRowIds.slot(), -1);
    status = manifests.install(
        directory, current, currentVersion.pageCount(), currentVersion.fileBytes(),
        currentVersion.slot(), -1, logical);
    return status;
  }

  private void finishCleanup(
      DurableDirectory directory, CheckpointState state, CheckpointManifestVersion version,
      CheckpointLogicalRowIdManifestReference logicalRowIds) {
    int pending = version.cleanupSlot();
    int logicalPending = logicalRowIds.cleanupSlot();
    if (pending < 0 && logicalPending < 0) return;
    if (!cleanup.remove(directory, pending).isOk()
        || !logicalCleanup.remove(directory, logicalPending).isOk()) return;
    CheckpointLogicalRowIdManifestReference logical = new CheckpointLogicalRowIdManifestReference();
    logical.set(
        logicalRowIds.count(), logicalRowIds.fileBytes(), logicalRowIds.digest(),
        logicalRowIds.slot(), -1);
    manifests.install(
        directory, state, version.pageCount(), version.fileBytes(), version.slot(), -1,
        logical);
  }
}
