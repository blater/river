package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Latches a quiescent drain only when exact commit demand exceeds durable row IDs. */
final class IndexedDurableVersionAdmission {
  private boolean maintenanceRequired;
  private long pressurePublishedRows;
  private long pressureObsoleteRows;

  StatusCode transactionAdmissionStatus() {
    return maintenanceRequired ? StatusCode.RETRY : StatusCode.OK;
  }

  StatusCode admit(long publishedRows, long obsoleteRows, int requiredVersions) {
    StatusCode valid = validate(publishedRows, obsoleteRows);
    if (!valid.isOk()) return valid;
    if (requiredVersions < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (maintenanceRequired) return StatusCode.RETRY;
    if (requiredVersions <= IndexedTableLimits.MAX_ROWS - publishedRows) {
      return StatusCode.OK;
    }
    if (obsoleteRows == 0) return StatusCode.RESOURCE_EXHAUSTED;
    maintenanceRequired = true;
    pressurePublishedRows = publishedRows;
    pressureObsoleteRows = obsoleteRows;
    return StatusCode.RETRY;
  }

  StatusCode maintenanceCompleted(long publishedRows, long obsoleteRows) {
    StatusCode valid = validate(publishedRows, obsoleteRows);
    if (!valid.isOk()) return valid;
    if (!maintenanceRequired) return StatusCode.OK;
    if (pressureObsoleteRows <= 0 || obsoleteRows != 0
        || publishedRows >= pressurePublishedRows) {
      return StatusCode.INVARIANT_BROKEN;
    }
    maintenanceRequired = false;
    pressurePublishedRows = pressureObsoleteRows = 0;
    return StatusCode.OK;
  }

  private static StatusCode validate(long publishedRows, long obsoleteRows) {
    return publishedRows < 0 || publishedRows > IndexedTableLimits.MAX_ROWS
            || obsoleteRows < 0 || obsoleteRows > publishedRows
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }
}
