package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import java.nio.ByteBuffer;

/** Atomically materializes a bounded chain of cardinality-changing query blocks. */
final class SqlBlockPipelineExecution {
  private final io.riverdb.engine.relational.RelationalSession session;
  private final BoundSqlStatement bound;
  private final SqlBlockPlanBinder binder;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBlockPredicateEvaluator predicates;
  private final SqlBlockPredicateEvaluator.Match match =
      new SqlBlockPredicateEvaluator.Match();
  private final SqlBlockPhysicalRowReader physical = new SqlBlockPhysicalRowReader();
  private final RelationalScanCursor physicalCursor = new RelationalScanCursor();
  private final RelationalScanResult physicalResult = new RelationalScanResult();
  private final SqlBlockRowStore first = new SqlBlockRowStore();
  private final SqlBlockRowStore second = new SqlBlockRowStore();
  private final SqlBlockRow sourceRow = new SqlBlockRow();
  private final SqlBlockRow projectedRow = new SqlBlockRow();
  private final SqlBlockRow lookaheadRow = new SqlBlockRow();
  private final SqlBlockRow groupKeyRow = new SqlBlockRow();
  private final SqlAggregateAccumulatorSet accumulator =
      new SqlAggregateAccumulatorSet();
  private final SqlHavingEvaluator having;
  private final long[] outputValues = new long[8];
  private final int[] outputTypes = new int[8];
  private final byte[] groupText = new byte[Utf8Text.MAXIMUM_BYTES];
  private ByteBuffer aggregateText;
  private SqlBlockRowStore finalStore;
  private SqlBlockSchema finalSchema;
  private long rows;
  private int activeBlock;

  SqlBlockPipelineExecution(
      io.riverdb.engine.relational.RelationalSession relationalSession,
      BoundSqlStatement statement,
      SqlBlockPlanBinder planBinder,
      SqlExpressionEvaluator expressions,
      SqlRowProjectionEvaluator projectionEvaluator) {
    session = relationalSession;
    bound = statement;
    binder = planBinder;
    projections = projectionEvaluator;
    predicates = new SqlBlockPredicateEvaluator(expressions, projectionEvaluator);
    having = new SqlHavingEvaluator(expressions, projectionEvaluator);
  }

  StatusCode prepare() {
    StatusCode status = close();
    SqlBlockRowStore input = null;
    for (int block = bound.blockPlans.count() - 1;
        status.isOk() && block >= 0; block--) {
      SqlBlockSchema child = block + 1 == bound.blockPlans.count()
          ? bound.blockPlans.baseSchema() : bound.blockPlans.schema(block + 1);
      status = binder.activate(bound, block, child);
      if (status.isOk()) status = projections.prepare(bound);
      if (!status.isOk()) break;
      SqlBlockRowStore output = input == first ? second : first;
      status = execute(block, input, output);
      input = status.isOk() ? finalStore : input;
    }
    if (status.isOk()) {
      finalStore = input;
      finalSchema = bound.blockPlans.schema(0);
      rows = 0;
    } else {
      close();
    }
    return status;
  }

  StatusCode next(SqlScanRowResult result) {
    if (finalStore == null || result == null) return StatusCode.CONFLICT;
    if (rows >= bound.blockPlans.command(0).rowLimit()) return StatusCode.CONFLICT;
    StatusCode status = finalStore.next(sourceRow);
    if (!status.isOk()) return status;
    long nullMask = 0;
    for (int column = 0; column < finalSchema.count(); column++) {
      outputValues[column] = sourceRow.value(column);
      outputTypes[column] = finalSchema.descriptor(column);
      if (sourceRow.nullValue(column)) nullMask |= 1L << column;
    }
    result.set(rows++, outputValues, nullMask, outputTypes, finalSchema.count());
    for (int column = 0; column < finalSchema.count(); column++) {
      if (finalSchema.varchar(column) && !sourceRow.nullValue(column)) {
        status = result.setTextAt(
            column, sourceRow.text(column), 0, sourceRow.textLength(column));
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  StatusCode next(SqlExecutionResult result, long commitSequence) {
    if (finalStore == null || result == null) return StatusCode.CONFLICT;
    if (rows >= bound.blockPlans.command(0).rowLimit()) return StatusCode.CONFLICT;
    StatusCode status = finalStore.next(sourceRow);
    if (!status.isOk()) return status;
    long nullMask = fillOutput();
    result.setProjection(
        rows++, outputValues, nullMask, outputTypes, finalSchema.count(), commitSequence);
    for (int column = 0; column < finalSchema.count(); column++) {
      if (finalSchema.varchar(column) && !sourceRow.nullValue(column)) {
        status = result.setTextAt(
            column, sourceRow.text(column), sourceRow.textLength(column));
        if (!status.isOk()) return status;
      }
    }
    return StatusCode.OK;
  }

  int columnCount() { return finalSchema == null ? 0 : finalSchema.count(); }
  CharSequence columnName(int column) { return finalSchema.name(column); }
  int typeDescriptor(int column) { return finalSchema.descriptor(column); }
  boolean nullable(int column) { return finalSchema.nullable(column); }
  long rowCount() { return finalStore == null ? 0 : finalStore.rowCount(); }
  boolean active() { return finalStore != null; }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    if (physicalCursor.isActive()) status = session.closeScan(physicalCursor);
    StatusCode firstStatus = first.close();
    StatusCode secondStatus = second.close();
    if (status.isOk()) status = firstStatus;
    if (status.isOk()) status = secondStatus;
    accumulator.clear(bound.aggregates);
    physical.reset();
    predicates.reset();
    sourceRow.reset(0);
    projectedRow.reset(0);
    lookaheadRow.reset(0);
    groupKeyRow.reset(0);
    for (int index = 0; index < groupText.length; index++) groupText[index] = 0;
    aggregateText = null;
    finalStore = null;
    finalSchema = null;
    rows = 0;
    return status;
  }

  private StatusCode execute(
      int block, SqlBlockRowStore input, SqlBlockRowStore output) {
    activeBlock = block;
    SqlCommand command = bound.command;
    SqlCommandType type = command.type();
    if (SqlBinder.isScalarAggregate(type)) {
      return scalar(block, input, output);
    }
    if (SqlBinder.isGroupAggregate(type)) {
      return grouped(block, input, output);
    }
    StatusCode status = materialize(block, input, output,
        type == SqlCommandType.DISTINCT_SCAN ? 0 : outputSortKey(block));
    if (!status.isOk()) return status;
    if (type != SqlCommandType.DISTINCT_SCAN) {
      finalStore = output;
      return StatusCode.OK;
    }
    SqlBlockRowStore distinct = input == null || input == first ? second : first;
    if (distinct == output) distinct = output == first ? second : first;
    status = deduplicate(block, output, distinct);
    finalStore = status.isOk() ? distinct : null;
    return status;
  }

  private StatusCode materialize(
      int block,
      SqlBlockRowStore input,
      SqlBlockRowStore output,
      int sortKey) {
    SqlBlockSchema schema = bound.blockPlans.operandSchema(block);
    StatusCode status = output.begin(schema, sortKey, bound.command.isDescendingOrder());
    if (status.isOk() && input == null) status = session.beginScan(bound.table, physicalCursor);
    while (status.isOk()) {
      status = nextSource(input);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = predicates.matches(
          bound.command,
          input == null ? bound.blockPlans.baseSchema()
              : bound.blockPlans.schema(block + 1),
          sourceRow,
          bound,
          match);
      if (!status.isOk() || !match.matched) continue;
      status = projections.projectBlock(sourceRow, projectedRow);
      if (status.isOk()) status = output.append(projectedRow);
    }
    if (input == null && physicalCursor.isActive()) {
      StatusCode closed = session.closeScan(physicalCursor);
      if (status.isOk()) status = closed;
    } else if (input != null) {
      StatusCode closed = input.close();
      if (status.isOk()) status = closed;
    }
    return status.isOk() ? output.finish() : status;
  }

  private StatusCode scalar(
      int block, SqlBlockRowStore input, SqlBlockRowStore output) {
    accumulator.reset(bound.aggregates);
    StatusCode status = input == null
        ? session.beginScan(bound.table, physicalCursor) : StatusCode.OK;
    while (status.isOk()) {
      status = nextSource(input);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = predicates.matches(
          bound.command,
          input == null ? bound.blockPlans.baseSchema()
              : bound.blockPlans.schema(block + 1),
          sourceRow,
          bound,
          match);
      if (!status.isOk() || !match.matched) continue;
      status = projections.projectBlock(sourceRow, projectedRow);
      if (status.isOk()) status = accumulator.accumulateBlock(bound.aggregates, projectedRow);
    }
    status = closeSource(input, status);
    if (status.isOk()) status = accumulator.finish(bound.aggregates);
    if (status.isOk()) status = having.evaluate(
        bound.command, bound.havingPrograms, accumulator, 0, true, null, 0);
    if (!status.isOk()) return status;
    status = output.begin(bound.blockPlans.schema(block), outputSortKey(block),
        bound.command.isDescendingOrder());
    if (status.isOk() && having.matched()) {
      status = publishAggregate(projectedRow, false);
      if (status.isOk()) status = output.append(projectedRow);
    }
    if (status.isOk()) status = output.finish();
    finalStore = status.isOk() ? output : null;
    return status;
  }

  private StatusCode grouped(
      int block, SqlBlockRowStore input, SqlBlockRowStore output) {
    StatusCode status = materialize(block, input, output, 0);
    if (!status.isOk()) return status;
    SqlBlockRowStore grouped = input == null || input == first ? second : first;
    if (grouped == output) grouped = output == first ? second : first;
    status = grouped.begin(
        bound.blockPlans.schema(block), outputSortKey(block),
        bound.command.isDescendingOrder());
    boolean lookahead = false;
    while (status.isOk()) {
      if (!lookahead) {
        status = output.next(sourceRow);
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (!status.isOk()) break;
      } else {
        sourceRow.copyFrom(lookaheadRow);
        lookahead = false;
      }
      groupKeyRow.copyFrom(sourceRow);
      accumulator.reset(bound.aggregates);
      do {
        status = accumulator.accumulateBlock(bound.aggregates, sourceRow);
        if (!status.isOk()) break;
        status = output.next(lookaheadRow);
        if (status == StatusCode.CONFLICT) {
          status = StatusCode.OK;
          break;
        }
        if (!status.isOk()) break;
        lookahead = !sameKey(groupKeyRow, lookaheadRow);
        if (!lookahead) sourceRow.copyFrom(lookaheadRow);
      } while (!lookahead);
      if (!status.isOk()) break;
      status = accumulator.finish(bound.aggregates);
      int groupLength = status.isOk() ? encodeGroupKey() : -1;
      if (status.isOk() && groupLength < 0) status = StatusCode.CORRUPTION;
      if (status.isOk()) status = having.evaluate(
          bound.command,
          bound.havingPrograms,
          accumulator,
          groupKeyRow.value(0),
          groupKeyRow.nullValue(0),
          groupText,
          groupLength);
      if (status.isOk() && having.matched()) {
        status = publishAggregate(projectedRow, true);
        if (status.isOk()) status = grouped.append(projectedRow);
      }
    }
    StatusCode closed = output.close();
    if (status.isOk()) status = closed;
    if (status.isOk()) status = grouped.finish();
    finalStore = status.isOk() ? grouped : null;
    return status;
  }

  private StatusCode deduplicate(
      int block, SqlBlockRowStore sorted, SqlBlockRowStore output) {
    StatusCode status = output.begin(bound.blockPlans.schema(block), -1, false);
    boolean available = false;
    while (status.isOk()) {
      status = sorted.next(sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      if (!available || !sameKey(groupKeyRow, sourceRow)) {
        groupKeyRow.copyFrom(sourceRow);
        status = output.append(sourceRow);
        available = true;
      }
    }
    StatusCode closed = sorted.close();
    if (status.isOk()) status = closed;
    return status.isOk() ? output.finish() : status;
  }

  private StatusCode nextSource(SqlBlockRowStore input) {
    if (input != null) return input.next(sourceRow);
    StatusCode status = session.nextScan(physicalCursor, physicalResult);
    return status.isOk()
        ? physical.read(
            physicalResult.key(), physicalResult.row(), bound.table, sourceRow)
        : status;
  }

  private StatusCode closeSource(SqlBlockRowStore input, StatusCode status) {
    StatusCode closed = input == null
        ? physicalCursor.isActive() ? session.closeScan(physicalCursor) : StatusCode.OK
        : input.close();
    return status.isOk() ? closed : status;
  }

  private StatusCode publishAggregate(SqlBlockRow row, boolean grouped) {
    int selected = bound.command.aggregateOutputInvocation(0);
    row.reset(grouped ? 2 : 1);
    if (grouped) {
      if (groupKeyRow.nullValue(0)) row.setNull(0);
      else {
        row.setValue(0, groupKeyRow.value(0));
        if (bound.blockPlans.schema(activeBlock).varchar(0)) {
          row.setText(0, groupKeyRow.text(0), 0, groupKeyRow.textLength(0));
        }
      }
    }
    int output = grouped ? 1 : 0;
    if (accumulator.nullValue(selected)) {
      row.setNull(output);
      return StatusCode.OK;
    }
    row.setValue(output, accumulator.value(selected));
    if (SqlTypeDescriptor.typeId(bound.aggregates.resultDescriptor(selected))
        != SqlTypeDescriptor.TYPE_ID_VARCHAR) return StatusCode.OK;
    int length = accumulator.textLength(selected);
    if (length == 0) {
      row.setText(output, row.text(output), 0, 0);
      return StatusCode.OK;
    }
    if (aggregateText == null) aggregateText = ByteBuffer.wrap(accumulator.text());
    int characters = Utf8Text.decode(
        aggregateText, accumulator.textOffset(selected), length, row.text(output), 0);
    if (characters < 0) return StatusCode.CORRUPTION;
    row.setText(output, row.text(output), 0, characters);
    return StatusCode.OK;
  }

  private int encodeGroupKey() {
    for (int index = 0; index < groupText.length; index++) groupText[index] = 0;
    if (groupKeyRow.nullValue(0)
        || !bound.blockPlans.operandSchema(activeBlock).varchar(0)) return 0;
    return Utf8Text.encode(
        groupKeyRow.text(0), 0, groupKeyRow.textLength(0),
        Utf8Text.MAXIMUM_SCALARS, groupText, 0);
  }

  private boolean sameKey(SqlBlockRow left, SqlBlockRow right) {
    if (left.nullValue(0) != right.nullValue(0)) return false;
    if (left.nullValue(0)) return true;
    if (SqlTypeDescriptor.typeId(bound.projectionPrograms.resultDescriptor(0))
        != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return left.value(0) == right.value(0);
    }
    if (left.textLength(0) != right.textLength(0)) return false;
    for (int index = 0; index < left.textLength(0); index++) {
      if (left.textCharacter(0, index) != right.textCharacter(0, index)) return false;
    }
    return true;
  }

  private int outputSortKey(int block) {
    return block == 0 && bound.command.isOrdered()
        ? bound.blockPlans.schema(block).find(bound.command.orderColumnName()) : -1;
  }

  private long fillOutput() {
    long nullMask = 0;
    for (int column = 0; column < finalSchema.count(); column++) {
      outputValues[column] = sourceRow.value(column);
      outputTypes[column] = finalSchema.descriptor(column);
      if (sourceRow.nullValue(column)) nullMask |= 1L << column;
    }
    return nullMask;
  }
}
