package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;

/** Resolves and validates one universal role scan before cursor creation. */
final class SqlUniversalDescriptorScanAdmission {
  private SqlUniversalDescriptorIndexAccess selected;
  private boolean empty;

  StatusCode prepare(
      RelationalSession session, SqlUniversalDescriptorName name, SchemaPin pin,
      TableDescriptor expected, SqlUniversalDescriptorIndexAccess access,
      SqlUniversalDescriptorIndexAccess fixedAccess,
      SqlUniversalJoinRows rows, SqlNestedRowProvider ancestors,
      boolean fullScan) {
    empty = false;
    selected = fixedAccess == null ? access : fixedAccess;
    StatusCode status = session.resolveDescriptor(name, pin, null);
    if (status.isOk() && !sameGeneration(expected, pin.descriptor())) {
      status = StatusCode.RETRY;
    }
    if (status.isOk() && fixedAccess != null && !fixedAccess.matches(pin.descriptor())) {
      status = StatusCode.RETRY;
    }
    if (!fullScan && status.isOk() && fixedAccess != null && fixedAccess.empty()) {
      empty = true;
    }
    if (!fullScan && status.isOk() && fixedAccess == null && selected.active()) {
      status = bind(access, rows, ancestors);
    }
    return status;
  }

  private StatusCode bind(
      SqlUniversalDescriptorIndexAccess access, SqlUniversalJoinRows rows,
      SqlNestedRowProvider ancestors) {
    StatusCode status = access.bind(rows, ancestors);
    if (status == StatusCode.CONFLICT) {
      empty = true;
      return StatusCode.OK;
    }
    return status;
  }

  boolean empty() { return empty; }
  SqlUniversalDescriptorIndexAccess selected() { return selected; }

  private static boolean sameGeneration(
      TableDescriptor expected, TableDescriptor candidate) {
    return candidate != null && candidate.tableId() == expected.tableId()
        && candidate.rowLayoutId() == expected.rowLayoutId()
        && candidate.catalogGeneration() == expected.catalogGeneration();
  }
}
