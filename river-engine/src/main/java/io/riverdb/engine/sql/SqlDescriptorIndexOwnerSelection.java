package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Retains one unambiguous descriptor-index owner selected by a catalog scan. */
final class SqlDescriptorIndexOwnerSelection {
  private final SchemaPin owner = new SchemaPin();
  private final SchemaPin candidate = new SchemaPin();
  private final SqlDescriptorObjectName tableName = new SqlDescriptorObjectName();
  private boolean found;
  private boolean collision;
  private boolean legacyIndex;

  StatusCode inspect(
      RelationalSession session,
      CharSequence candidateName,
      CharSequence indexName,
      CharSequence renamedName,
      StatusDetail detail) {
    SchemaPin pin = found ? candidate : owner;
    StatusCode status = session.resolveDescriptor(candidateName, pin, detail);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    TableDescriptor table = pin.descriptor();
    if (SqlDescriptorIndexNames.matches(table, renamedName)) collision = true;
    boolean matches = table.findSecondaryKey(indexName) >= 0;
    boolean primary = SqlDescriptorIndexNames.primary(table, indexName);
    if (!matches && !primary) return pin.release();
    if (primary) status = StatusCode.INVALID_EXTERNAL_INPUT;
    else if (found) status = StatusCode.CONFLICT;
    else {
      tableName.set(candidateName);
      found = true;
    }
    return releaseCandidate(status);
  }

  StatusCode finish(StatusCode status) {
    if (status.isOk() && !found) {
      legacyIndex = true;
      status = StatusCode.CONFLICT;
    }
    if (status.isOk() && collision) status = StatusCode.CONFLICT;
    if (!status.isOk()) release();
    return status;
  }

  StatusCode reset() {
    StatusCode status = release();
    found = false;
    collision = false;
    legacyIndex = false;
    return status;
  }

  SchemaPin owner() { return owner; }
  CharSequence tableName() { return tableName; }
  boolean legacyIndex() { return legacyIndex; }

  StatusCode release() {
    StatusCode status = candidate.isActive() ? candidate.release() : StatusCode.OK;
    if (owner.isActive()) {
      StatusCode released = owner.release();
      if (status.isOk()) status = released;
    }
    tableName.reset();
    return status;
  }

  private StatusCode releaseCandidate(StatusCode status) {
    if (!candidate.isActive()) return status;
    StatusCode released = candidate.release();
    return status.isOk() ? released : status;
  }
}
