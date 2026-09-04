package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.tx.api.lock.LockMode;

/** Retains one descriptor tuple-index access decision and its typed bound values. */
final class SqlDescriptorIndexAccess {
  private final SqlDescriptorIndexBoundsPreparation preparation =
      new SqlDescriptorIndexBoundsPreparation();
  private final SqlDescriptorIndexChoice choice = new SqlDescriptorIndexChoice();
  private boolean active;
  private boolean exactUnique;

  StatusCode prepare(
      SqlCommand command, TableDescriptor table, SqlDescriptorPredicateBindings bindings,
      int orderCount, int[] orderColumns, boolean[] descending) {
    reset();
    SqlBooleanPredicateProgram program = command.wherePredicates();
    if (program.isAvailable()
        && !SqlPredicateConjunction.only(program, program.root())) return StatusCode.OK;
    exactUnique = SqlDescriptorSingletonProof.exact(table, bindings);
    SqlDescriptorIndexSelection.choose(
        table, bindings, orderCount, orderColumns, descending, choice);
    if (choice.key == null) return StatusCode.OK;
    StatusCode status = preparation.prepare(command, table, bindings, choice);
    if (status.isOk()) active = true;
    return status;
  }

  boolean active() { return active; }
  /** Physical source classification; semantic singleton proof is intentionally separate. */
  boolean physicalExactUnique() {
    return active && choice.key.isUnique() && choice.equalityParts == choice.key.partCount();
  }
  LockMode serializableSourceMode(boolean lockingSource) {
    if (!lockingSource) return LockMode.SHARED;
    return physicalExactUnique() ? LockMode.EXCLUSIVE : LockMode.UPDATE;
  }
  boolean exactUnique() { return active && exactUnique; }
  boolean orderCovered() { return active && choice.orderCovered; }
  io.riverdb.engine.relational.RelationalDescriptorIndexBounds bounds() {
    return preparation.bounds();
  }
  KeyDescriptor key() { return choice.key; }

  int accessColumn() {
    if (choice.key.kind() == KeyDescriptor.KIND_PRIMARY) return 0;
    for (int part = 0; part < choice.key.partCount(); part++) {
      if (choice.key.columnOrdinalAt(part) > 0) return choice.key.columnOrdinalAt(part);
    }
    return 1;
  }

  void reset() {
    active = false;
    exactUnique = false;
    preparation.reset();
    choice.reset();
  }

}
