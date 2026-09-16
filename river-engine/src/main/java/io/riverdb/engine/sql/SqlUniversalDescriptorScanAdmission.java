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
    if (status.isOk()) status = validateGeneration(expected, pin);
    if (status.isOk()) status = validateFixedAccess(fixedAccess, pin);
    if (status.isOk()) status = markFixedEmpty(fullScan, fixedAccess);
    if (status.isOk()) status = bindSelected(fullScan, fixedAccess, access, rows, ancestors);
    return status;
  }

  private static StatusCode validateGeneration(TableDescriptor expected, SchemaPin pin) {
    return sameGeneration(expected, pin.descriptor()) ? StatusCode.OK : StatusCode.RETRY;
  }

  private static StatusCode validateFixedAccess(
      SqlUniversalDescriptorIndexAccess fixedAccess, SchemaPin pin) {
    return fixedAccess == null || fixedAccess.matches(pin.descriptor())
        ? StatusCode.OK : StatusCode.RETRY;
  }

  private StatusCode markFixedEmpty(
      boolean fullScan, SqlUniversalDescriptorIndexAccess fixedAccess) {
    if (!fullScan && fixedAccess != null && fixedAccess.empty()) empty = true;
    return StatusCode.OK;
  }

  private StatusCode bindSelected(
      boolean fullScan, SqlUniversalDescriptorIndexAccess fixedAccess,
      SqlUniversalDescriptorIndexAccess access, SqlUniversalJoinRows rows,
      SqlNestedRowProvider ancestors) {
    if (fullScan || fixedAccess != null || !selected.active()) return StatusCode.OK;
    return bind(access, rows, ancestors);
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
