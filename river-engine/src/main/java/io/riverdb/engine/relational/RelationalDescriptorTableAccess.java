package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedLogicalRowIdReservation;
import io.riverdb.tx.api.TransactionState;

/** Per-session allocation-free row access for catalog-v2 descriptor tables. */
public final class RelationalDescriptorTableAccess {
  private final RelationalSession owner;
  private final IndexedTransactionSession session;
  private final RelationalDescriptorBatchInsert batchInsert;
  private final RelationalDescriptorIndexBackfill indexBackfill;
  private final RelationalDescriptorTupleMutations tupleMutations =
      new RelationalDescriptorTupleMutations();
  private final RelationalDescriptorForeignKeyChecks foreignKeyChecks;
  private final RelationalDescriptorRowAccess rowAccess = new RelationalDescriptorRowAccess();
  private final RelationalDescriptorLockedRows lockedRows;
  private final RelationalDescriptorPrimaryAccess primaryAccess =
      new RelationalDescriptorPrimaryAccess();
  private final RelationalDescriptorCheckValidation checks =
      new RelationalDescriptorCheckValidation();
  private final RelationalDescriptorScanAccess scanAccess;
  private final IndexedLogicalRowIdReservation reserved =
      new IndexedLogicalRowIdReservation();
  private final RelationalRowIdentityResult resolved = new RelationalRowIdentityResult();

  RelationalDescriptorTableAccess(
      RelationalSession relationalSession,
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices) {
    owner = relationalSession;
    session = indexedSession;
    scanAccess = new RelationalDescriptorScanAccess(indexedSession, rowAccess);
    lockedRows = new RelationalDescriptorLockedRows(indexedSession, rowAccess);
    foreignKeyChecks = new RelationalDescriptorForeignKeyChecks(
        relationalSession, indexedSession, databaseServices);
    batchInsert = new RelationalDescriptorBatchInsert(
        relationalSession, indexedSession);
    indexBackfill = new RelationalDescriptorIndexBackfill(
        relationalSession, this, databaseServices == null ? null
            : databaseServices.newDescriptorIndexBuildSession());
  }

  /** Reusable statement-wide INSERT admission and staging owned by this session. */
  public RelationalDescriptorBatchInsert batchInsert() { return batchInsert; }

  /** Reusable private-index backfill owner bound to this transaction session. */
  public RelationalDescriptorIndexBackfill indexBackfill() { return indexBackfill; }

  StatusCode checkDrop(TableDescriptor table) {
    return foreignKeyChecks.checkDrop(table);
  }

  public StatusCode insert(
      SchemaPin pin, SqlValueBuffer values, RelationalRowIdentityResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = validTable(pin, values);
    if (table == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = prepareInsert(table, values);
    if (!status.isOk()) return status;
    long logicalRowId = reserved.firstLogicalRowId();
    status = rowAccess.encode(table, logicalRowId, values);
    if (!status.isOk()) return status;
    status = stageInsert(table, values, logicalRowId);
    if (status.isOk()) result.set(logicalRowId);
    return status;
  }

  public StatusCode update(
      SchemaPin pin, long currentPrimaryKey, SqlValueBuffer values) {
    TableDescriptor table = validDescriptor(pin);
    StatusCode status = primaryAccess.scalarValues(table, currentPrimaryKey);
    return status.isOk() ? update(pin, primaryAccess.scalarValues(), values) : status;
  }

  public StatusCode update(
      SchemaPin pin, SqlValueBuffer currentPrimaryKey, SqlValueBuffer values) {
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = validTable(pin, values);
    if (table == null || currentPrimaryKey == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = lockedRows.lockPoint(table, currentPrimaryKey);
    if (!status.isOk()) return status;
    long logicalRowId = lockedRows.logicalRowId();
    status = prepareUpdate(table, logicalRowId, values);
    if (!status.isOk()) return releaseCurrent(status);
    return stageUpdate(table, logicalRowId, values);
  }

  public StatusCode fetch(
      SchemaPin pin, long primaryKey, SqlValueBuffer destination) {
    TableDescriptor table = validDescriptor(pin);
    StatusCode status = primaryAccess.scalarValues(table, primaryKey);
    return status.isOk() ? fetch(pin, primaryAccess.scalarValues(), destination) : status;
  }

  public StatusCode fetch(
      SchemaPin pin, SqlValueBuffer primaryKey, SqlValueBuffer destination) {
    return fetch(pin, primaryKey, destination, null);
  }

  public StatusCode fetch(
      SchemaPin pin, SqlValueBuffer primaryKey, SqlValueBuffer destination,
      RelationalRowIdentityResult result) {
    if (result != null) result.reset();
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = validDescriptor(pin);
    if (table == null || table.primaryKey() == null
        || primaryKey == null || destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = rowAccess.reserve(table);
    if (status.isOk()) status = logicalRowId(table, primaryKey, resolved);
    if (!status.isOk()) return status;
    long logicalRowId = resolved.logicalRowId();
    status = fetchBase(table, logicalRowId, destination);
    if (status.isOk()) status = validateResolvedPrimary(table, destination);
    if (status.isOk() && result != null) result.set(logicalRowId);
    return status;
  }

  /** Resolves a point candidate and decodes its logical-row-lock-protected current row. */
  public StatusCode fetchLockedCandidate(
      SchemaPin pin, SqlValueBuffer primaryKey, SqlValueBuffer destination,
      RelationalRowIdentityResult result) {
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = validDescriptor(pin);
    if (table == null || table.primaryKey() == null
        || primaryKey == null || destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return lockedRows.lockPoint(table, primaryKey, destination, result);
  }

  /** Stages a replacement built from the row returned by the latest protected source fetch. */
  public StatusCode updateLocked(SchemaPin pin, SqlValueBuffer values) {
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = validTable(pin, values);
    long logicalRowId = lockedRows.logicalRowId();
    if (table == null || logicalRowId <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = prepareUpdate(table, logicalRowId, values);
    if (!status.isOk()) return releaseCurrent(status);
    return stageUpdate(table, logicalRowId, values);
  }

  StatusCode fetchByLogicalRowId(
      SchemaPin pin, long logicalRowId, SqlValueBuffer destination) {
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = validDescriptor(pin);
    if (table == null || logicalRowId <= 0 || destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = rowAccess.reserve(table);
    if (status.isOk()) status = fetchBase(table, logicalRowId, destination);
    return status;
  }

  public StatusCode delete(SchemaPin pin, long primaryKey) {
    TableDescriptor table = validDescriptor(pin);
    StatusCode status = primaryAccess.scalarValues(table, primaryKey);
    return status.isOk() ? delete(pin, primaryAccess.scalarValues()) : status;
  }

  public StatusCode delete(SchemaPin pin, SqlValueBuffer primaryKey) {
    if (!active()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = validDescriptor(pin);
    if (table == null || table.primaryKey() == null || primaryKey == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lockedRows.lockPoint(table, primaryKey);
    if (!status.isOk()) return status;
    long logicalRowId = lockedRows.logicalRowId();
    status = prepareDelete(table, logicalRowId);
    if (!status.isOk()) return releaseCurrent(status);
    status = session.deleteLocked(lockedRows.locked());
    return status.isOk() ? tupleMutations.stage(session, table, logicalRowId) : status;
  }

  /** Updates the row most recently published by this owned scan cursor. */
  public StatusCode updateScanned(
      RelationalDescriptorScanCursor cursor, SqlValueBuffer values) {
    if (!active() || cursor == null || !cursor.matches(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TableDescriptor table = cursor.descriptor();
    if (values == null || values.count() != table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long logicalRowId = cursor.logicalRowId();
    StatusCode status = lockedRows.logicalRowId() == logicalRowId
        ? prepareUpdate(table, logicalRowId, values) : StatusCode.INVALID_EXTERNAL_INPUT;
    if (!status.isOk()) return releaseCurrent(status);
    return stageUpdate(table, logicalRowId, values);
  }

  /** Deletes the row most recently published by this owned scan cursor. */
  public StatusCode deleteScanned(RelationalDescriptorScanCursor cursor) {
    if (!active() || cursor == null || !cursor.matches(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TableDescriptor table = cursor.descriptor();
    long logicalRowId = cursor.logicalRowId();
    StatusCode status = lockedRows.logicalRowId() == logicalRowId
        ? prepareDelete(table, logicalRowId) : StatusCode.INVALID_EXTERNAL_INPUT;
    if (!status.isOk()) return releaseCurrent(status);
    status = session.deleteLocked(lockedRows.locked());
    return status.isOk() ? tupleMutations.stage(session, table, logicalRowId) : status;
  }

  /** Opens a logical-row scan and transfers the supplied schema pin into the cursor. */
  public StatusCode beginScan(SchemaPin pin, RelationalDescriptorScanCursor cursor) {
    TableDescriptor table = validDescriptor(pin);
    return !active() || table == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : scanAccess.begin(this, pin, table, cursor);
  }

  /** Opens a tuple-index scan and transfers the supplied schema pin into the cursor. */
  public StatusCode beginIndexScan(
      SchemaPin pin, RelationalDescriptorIndexBounds bounds,
      RelationalDescriptorScanCursor cursor) {
    TableDescriptor table = validDescriptor(pin);
    return !active() || table == null || bounds == null || bounds.key() == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : scanAccess.beginIndex(this, pin, table, bounds, cursor);
  }

  /** Decodes the next scanned row into caller-owned values and publishes its stable identity. */
  public StatusCode nextScan(
      RelationalDescriptorScanCursor cursor,
      SqlValueBuffer destination,
      RelationalRowIdentityResult result) {
    return !active() ? StatusCode.INVALID_EXTERNAL_INPUT
        : scanAccess.next(this, cursor, destination, result);
  }

  public StatusCode closeScan(RelationalDescriptorScanCursor cursor) {
    return scanAccess.close(this, cursor);
  }

  /** Replaces the last scanned values with the lock-protected current row. */
  public StatusCode lockScannedCandidate(
      RelationalDescriptorScanCursor cursor, SqlValueBuffer destination,
      RelationalLockedCandidateResult result) {
    if (result != null) result.reset();
    if (!active() || cursor == null || !cursor.matches(this) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lockedRows.lockScan(cursor, destination);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    return status.isOk() ? result.publishLocked() : status;
  }

  /** Decodes one authorized logical row under a borrowed current-row guard. */
  public StatusCode lockLogicalCandidate(
      TableDescriptor table, long logicalRowId, SqlValueBuffer destination,
      RelationalLockedCandidateResult result) {
    if (result != null) result.reset();
    if (!active() || table == null || logicalRowId <= 0
        || destination == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lockedRows.lockLogical(table, logicalRowId, destination);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    return status.isOk() ? result.publishLocked() : status;
  }

  public StatusCode retainCurrent() { return lockedRows.retain(); }

  public StatusCode releaseCurrent() { return lockedRows.release(); }

  public boolean currentBorrowed() { return lockedRows.borrowed(); }

  StatusCode closeActiveScan() {
    return scanAccess.closeActive(this);
  }

  StatusCode closeSession() {
    StatusCode status = closeActiveScan();
    return status.isOk() ? indexBackfill.closeSession() : status;
  }

  StatusCode reserveLogicalRowIds(
      long objectId, int count, IndexedLogicalRowIdReservation result) {
    return session.reserveLogicalRowIds(objectId, count, result);
  }

  private StatusCode fetchBase(
      TableDescriptor table, long logicalRowId, SqlValueBuffer destination) {
    return rowAccess.fetch(session, table, logicalRowId, destination);
  }

  private StatusCode preflightMutation(TableDescriptor table, int rowBytes) {
    return tupleMutations.preflightSingleRow(session, table, rowBytes);
  }

  private StatusCode prepareInsert(
      TableDescriptor table, SqlValueBuffer values) {
    StatusCode status = rowAccess.reserve(table);
    if (!status.isOk()) return status;
    status = rowAccess.encode(table, 1, values);
    if (!status.isOk()) return status;
    status = checks.validate(table, values);
    if (!status.isOk()) return status;
    status = tupleMutations.planInsert(table, values, 1);
    if (!status.isOk()) return status;
    status = preflightMutation(table, rowAccess.length());
    if (status.isOk()) {
      status = owner.reserveDescriptorLogicalRowId(table.tableId(), 1, reserved);
    }
    if (status.isOk()) status = tupleMutations.bindLogicalRowId(reserved.firstLogicalRowId());
    return status.isOk() ? tupleMutations.validateInsert(
        session, table, values, reserved.firstLogicalRowId()) : status;
  }

  private StatusCode stageInsert(
      TableDescriptor table, SqlValueBuffer values, long logicalRowId) {
    StatusCode status = session.insert(
        RelationalDescriptorKeyspace.baseRows(table.tableId()), logicalRowId,
        rowAccess.bytes());
    return status.isOk() ? tupleMutations.stage(session, table, logicalRowId) : status;
  }

  private StatusCode prepareUpdate(
      TableDescriptor table, long logicalRowId, SqlValueBuffer values) {
    StatusCode status = rowAccess.encode(table, logicalRowId, values);
    if (!status.isOk()) return status;
    status = checks.validate(table, values);
    if (!status.isOk()) return status;
    status = tupleMutations.planUpdate(
        table, lockedRows.before(), values, logicalRowId);
    if (!status.isOk()) return status;
    status = preflightMutation(table, rowAccess.length());
    if (status.isOk()) status = tupleMutations.protect(session, table);
    if (status.isOk()) status = foreignKeyChecks.checkUpdate(
        table, lockedRows.before(), values, logicalRowId);
    return status.isOk()
        ? tupleMutations.validateUpdate(
            session, table, lockedRows.before(), values, logicalRowId) : status;
  }

  private StatusCode stageUpdate(
      TableDescriptor table, long logicalRowId, SqlValueBuffer values) {
    StatusCode status = session.updateLocked(lockedRows.locked(), rowAccess.bytes());
    return status.isOk() ? tupleMutations.stage(session, table, logicalRowId) : status;
  }

  private StatusCode releaseCurrent(StatusCode original) {
    StatusCode released = lockedRows.release();
    return released.isOk() ? original : released;
  }

  private StatusCode prepareDelete(
      TableDescriptor table, long logicalRowId) {
    return tupleMutations.prepareDelete(
        session, table, lockedRows.before(), logicalRowId, foreignKeyChecks);
  }

  private StatusCode logicalRowId(
      TableDescriptor table, SqlValueBuffer primaryValues,
      RelationalRowIdentityResult result) {
    return primaryAccess.resolve(session, table, primaryValues, result);
  }

  private StatusCode validateResolvedPrimary(
      TableDescriptor table, SqlValueBuffer values) {
    return primaryAccess.validateResolved(table, values);
  }

  private TableDescriptor validTable(
      SchemaPin pin, SqlValueBuffer values) {
    return RelationalDescriptorPin.validTable(owner, pin, values);
  }

  private boolean active() {
    return session != null && session.transaction().state() == TransactionState.ACTIVE;
  }

  private TableDescriptor validDescriptor(SchemaPin pin) {
    return RelationalDescriptorPin.validTable(owner, pin);
  }

}
