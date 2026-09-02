package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.sql.SqlExecutionResult;
import io.riverdb.engine.sql.SqlPreparedPlan;
import io.riverdb.engine.sql.SqlScanCursor;
import io.riverdb.engine.sql.SqlScanRowResult;
import io.riverdb.engine.sql.SqlSession;

/** Executes command, singleton-query, and row-set steps over one shared value path. */
final class TransactionProgramSteps {
  private final SqlSession session;
  private final TransactionProgramValues values;
  private final SqlExecutionResult execution;
  private final SqlScanCursor scan = new SqlScanCursor();
  private final SqlScanRowResult row = new SqlScanRowResult();
  private final TransactionSqlRowReader reader = new TransactionSqlRowReader();
  private StatusCode status = StatusCode.OK;

  TransactionProgramSteps(
      SqlSession sqlSession, TransactionProgramValues programValues,
      SqlExecutionResult executionResult) {
    session = sqlSession;
    values = programValues;
    execution = executionResult;
  }

  StatusCode status() { return status; }

  StatusCode closeOpenScan() {
    return scan.isActive() ? session.closeScan(scan, execution) : StatusCode.OK;
  }

  int execute(
      TransactionProgram program,
      TransactionProgramArguments arguments,
      int step,
      SqlPreparedPlan plan,
      TransactionProgramResult result) {
    status = values.bind(program, arguments, step);
    if (!status.isOk()) return Integer.MIN_VALUE;
    return plan.query()
        ? executeQuery(program, step, plan, result)
        : executeCommand(program, step, plan, result);
  }

  private int executeCommand(
      TransactionProgram program, int step,
      SqlPreparedPlan plan, TransactionProgramResult result) {
    status = session.executePrepared(plan, values.parameters(), execution);
    if (status.isOk() && (execution.affectedRows() < program.minimumAffectedRows(step)
        || execution.affectedRows() > program.maximumAffectedRows(step))) {
      status = StatusCode.CARDINALITY_VIOLATION;
    }
    if (status.isOk()) {
      status = result.beginStepResult(step, program.action(step), execution.affectedRows());
    }
    if (status.isOk() && program.captureCount(step) > 0) {
      if (!execution.hasValue()) status = StatusCode.CARDINALITY_VIOLATION;
      else {
        reader.pointTo(execution);
        status = values.captureOutput(
            program, step, reader, reader.columnCount(), result);
      }
    }
    return status.isOk() ? step + 1 : Integer.MIN_VALUE;
  }

  private int executeQuery(
      TransactionProgram program, int step,
      SqlPreparedPlan plan, TransactionProgramResult result) {
    status = scan.reset();
    if (status.isOk()) status = session.beginPreparedScan(plan, values.parameters(), scan);
    if (!status.isOk()) return Integer.MIN_VALUE;
    return program.action(step) == TransactionProgramAction.ROW_SET
        ? executeRowSet(program, step, result)
        : executeSingleRow(program, step, result);
  }

  private int executeSingleRow(
      TransactionProgram program, int step, TransactionProgramResult result) {
    boolean available = nextRow();
    if (!status.isOk()) return closeFailedScan();
    int action = program.action(step);
    status = result.beginStepResult(step, action, available ? 1 : 0);
    if (status.isOk() && available) {
      reader.pointTo(row);
      status = values.captureDataflow(program, step, reader, reader.columnCount());
      if (status.isOk() && program.captureCount(step) > 0) {
        status = values.captureOutput(
            program, step, reader, reader.columnCount(), result);
      }
    }
    if (status.isOk() && available && nextRow()) status = StatusCode.CARDINALITY_VIOLATION;
    if (status.isOk() && !available && action == TransactionProgramAction.EXACT_ONE) {
      status = StatusCode.CARDINALITY_VIOLATION;
    }
    StatusCode closed = session.closeScan(scan, execution);
    if (status.isOk()) status = closed;
    if (!status.isOk()) return Integer.MIN_VALUE;
    return !available && program.emptyTarget(step) >= 0
        ? program.emptyTarget(step) : step + 1;
  }

  private int executeRowSet(
      TransactionProgram program, int step, TransactionProgramResult result) {
    status = result.beginStepResult(step, TransactionProgramAction.ROW_SET, 0);
    while (status.isOk() && nextRow()) {
      reader.pointTo(row);
      status = values.captureOutput(program, step, reader, reader.columnCount(), result);
    }
    StatusCode closed = session.closeScan(scan, execution);
    if (status.isOk()) status = closed;
    return status.isOk() ? step + 1 : Integer.MIN_VALUE;
  }

  private boolean nextRow() {
    row.reset();
    status = session.nextScan(scan, row);
    if (status == StatusCode.CONFLICT && !row.isAvailable()) {
      status = StatusCode.OK;
      return false;
    }
    return status.isOk() && row.isAvailable();
  }

  private int closeFailedScan() {
    StatusCode primary = status;
    closeOpenScan();
    status = primary;
    return Integer.MIN_VALUE;
  }
}
