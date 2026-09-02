package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;

/** Executes the named point subset directly against catalog-v2 logical rows. */
final class SqlDescriptorPointExecution {
  private final RelationalSession session;
  private final SchemaPin pin = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);
  private final SqlDescriptorColumnMapping columns = new SqlDescriptorColumnMapping();
  private final SqlDescriptorMutationValues values = new SqlDescriptorMutationValues();
  private final SqlDescriptorProjection projection = new SqlDescriptorProjection();
  private final SqlDescriptorPredicate predicate = new SqlDescriptorPredicate();
  private final SqlDescriptorPrimaryPredicate primary = new SqlDescriptorPrimaryPredicate();
  private final SqlRowProjectionEvaluator expressions;
  private final SqlDescriptorBoundPredicate boundPredicate;
  private final SqlDescriptorPointInsertExecution insertExecution;
  private final SqlDescriptorPointScanExecution scanExecution;
  private final SqlDescriptorAggregateExecution aggregates;
  private final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  private boolean prepared;
  private int affectedRows;

  SqlDescriptorPointExecution(
      RelationalSession relationalSession,
      SqlRowProjectionEvaluator expressions,
      SqlBoundPredicateEvaluator predicateEvaluator,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget shapeBudget) {
    session = relationalSession;
    this.expressions = expressions;
    boundPredicate = new SqlDescriptorBoundPredicate(predicateEvaluator, shapeBudget);
    aggregates = new SqlDescriptorAggregateExecution(
        relationalSession, temporal, shapeBudget);
    insertExecution = new SqlDescriptorPointInsertExecution(
        session, columns, values, expressions, shapeBudget);
    scanExecution = new SqlDescriptorPointScanExecution(
        session, values, columns, projection, predicate, boundPredicate, expressions);
  }

  StatusCode prepare(SqlCommand command) {
    prepared = false;
    affectedRows = 0;
    if (!handles(command.type())) return StatusCode.CONFLICT;
    StatusCode status = close();
    if (status.isOk()) status = session.resolveDescriptor(command.tableName(), pin, detail);
    if (status.isOk()) prepared = true;
    return status;
  }

  StatusCode execute(SqlCommand command, SqlExecutionResult result) {
    if (!prepared || !pin.isActive()) return StatusCode.CONFLICT;
    StatusCode status = switch (command.type()) {
      case INSERT -> insertExecution.execute(command, pin);
      case UPDATE -> update(command);
      case DELETE -> delete(command);
      case SELECT, SCAN -> command.aggregateInvocationCount() == 0
          ? select(command, result) : aggregates.execute(command, pin, result);
      case COUNT, COUNT_VALUE, COUNT_DISTINCT, SUM, AVG, MIN, MAX ->
          aggregates.execute(command, pin, result);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
    if (command.type() == SqlCommandType.INSERT) affectedRows = insertExecution.affectedRows();
    StatusCode closed = close();
    return status.isOk() ? closed : status;
  }

  StatusCode prepareBinding(TableDefinition target) {
    return prepared && pin.isActive()
        ? boundPredicate.prepareBinding(pin.descriptor(), target) : StatusCode.CONFLICT;
  }

  StatusCode prepareBoundPredicate() {
    return prepared && pin.isActive()
        ? boundPredicate.prepare(pin.descriptor()) : StatusCode.CONFLICT;
  }

  int affectedRows() { return affectedRows; }
  boolean hasResources() {
    return scanExecution.hasResources() || aggregates.hasResources() || pin.isActive();
  }

  StatusCode close() {
    prepared = false;
    boundPredicate.reset();
    StatusCode status = scanExecution.close();
    StatusCode aggregateStatus = aggregates.close();
    if (status.isOk()) status = aggregateStatus;
    StatusCode pinStatus = pin.isActive() ? pin.release() : StatusCode.OK;
    return status.isOk() ? pinStatus : status;
  }

  private StatusCode update(SqlCommand command) {
    TableDescriptor table = pin.descriptor();
    StatusCode status = primary.bind(command, table);
    if (status == StatusCode.CONFLICT) {
      status = scanExecution.update(command, pin);
      affectedRows = scanExecution.affectedRows();
      return status;
    }
    if (status.isOk()) status = values.reserve(table);
    if (status.isOk()) status = session.descriptorRows().fetchLockedCandidate(
        pin, primary.values(), values.fetched(), identity);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (status.isOk()) status = columns.mapUpdate(command, table);
    if (status.isOk()) status = values.buildUpdate(
        command, table, columns, expressions);
    if (status.isOk()) status = session.descriptorRows().updateLocked(
        pin, values.mutation());
    if (status.isOk()) affectedRows = 1;
    if (session.descriptorRows().currentBorrowed()) {
      StatusCode released = session.descriptorRows().releaseCurrent();
      if (status.isOk()) status = released;
    }
    return status;
  }

  private StatusCode delete(SqlCommand command) {
    StatusCode status = primary.bind(command, pin.descriptor());
    if (status == StatusCode.CONFLICT) {
      status = scanExecution.delete(command, pin);
      affectedRows = scanExecution.affectedRows();
      return status;
    }
    if (status.isOk()) status = session.descriptorRows().delete(pin, primary.values());
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (status.isOk()) affectedRows = 1;
    return status;
  }

  private StatusCode select(SqlCommand command, SqlExecutionResult result) {
    TableDescriptor table = pin.descriptor();
    if (command.isOrdered() || command.aggregateInvocationCount() != 0) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    StatusCode status = primary.bind(command, table);
    if (status == StatusCode.CONFLICT) return scanExecution.select(command, pin, result);
    if (status.isOk()) status = values.reserve(table);
    if (status.isOk()) status = command.isSelectForUpdate()
        ? session.descriptorRows().fetchLockedCandidate(
            pin, primary.values(), values.fetched(), identity)
        : session.descriptorRows().fetch(
            pin, primary.values(), values.fetched(), identity);
    if (status.isOk()) status = projection.prepare(command, table);
    if (status.isOk()) status = projection.publish(
        values.fetched(), SqlDescriptorPublicRowKey.from(table, values.fetched()),
        session.visibleCommitSequence(), result);
    return command.isSelectForUpdate() ? finishLockedSelect(status) : status;
  }

  private StatusCode finishLockedSelect(StatusCode original) {
    if (!session.descriptorRows().currentBorrowed()) return original;
    if (original.isOk()) return session.descriptorRows().retainCurrent();
    StatusCode released = session.descriptorRows().releaseCurrent();
    return released.isOk() ? original : released;
  }

  private static boolean handles(SqlCommandType type) {
    return type == SqlCommandType.INSERT || type == SqlCommandType.UPDATE
        || type == SqlCommandType.DELETE || type == SqlCommandType.SELECT
        || type == SqlCommandType.SCAN || type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE || type == SqlCommandType.COUNT_DISTINCT
        || SqlBinder.isValueAggregate(type);
  }
}
