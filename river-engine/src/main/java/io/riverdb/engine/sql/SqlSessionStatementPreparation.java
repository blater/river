package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.SessionAuthorizer;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlRuntimeParameterBindings;
import io.riverdb.sql.SqlStatementTemplate;

/** Owns reusable SQL parsing, authorization, parameter binding, and preparation. */
final class SqlSessionStatementPreparation {
  private final RelationalSession session;
  private final SessionAuthorizer authorizer;
  private final SqlParser parser = new SqlParser();
  private final SqlRuntimeParameterBindings runtimeParameters =
      new SqlRuntimeParameterBindings();
  private final SqlStatementTemplate.Result templateResult =
      new SqlStatementTemplate.Result();
  private final BoundSqlStatement bound;
  private final SqlBinder binder;
  private final SqlBindingTableResolver bindingTables = new SqlBindingTableResolver();
  private final SqlPreparedValidation validation;
  private long preparedCompiles;
  private long preparedExecutions;
  private long preparedRecompiles;
  private boolean recompile;
  private long bindingCatalogGeneration;

  SqlSessionStatementPreparation(
      RelationalSession relationalSession,
      SessionAuthorizer sessionAuthorizer,
      BoundSqlStatement boundStatement,
      SqlBinder sqlBinder,
      SqlAtomicStatementLifecycle atomicLifecycle) {
    session = relationalSession;
    authorizer = sessionAuthorizer;
    bound = boundStatement;
    binder = sqlBinder;
    validation = new SqlPreparedValidation(
        session, this, parser, bound, bindingTables, atomicLifecycle);
  }

  BoundSqlStatement bound() { return bound; }

  long preparedCompiles() { return preparedCompiles; }
  long preparedExecutions() { return preparedExecutions; }
  long preparedRecompiles() { return preparedRecompiles; }

  StatusCode parseInvocation(String sql, ParameterSet parameters) {
    if (parameters == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = parser.parseTemplate(sql, bound.query, bound.command);
    if (status.isOk() && parser.templateParameterCount() != parameters.count()) {
      status = StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    if (status.isOk()) status = loadParameters(parameters, parser.templateParameterCount());
    if (status.isOk()) status = runtimeParameters.materialize(bound.query, bound.command);
    runtimeParameters.reset();
    return status;
  }

  StatusCode parseScan(String sql, ParameterSet parameters, boolean typed) {
    bound.reset();
    StatusCode status = typed
        ? parseInvocation(sql, parameters)
        : parser.parseQuery(sql, bound.query, bound.command);
    if (status.isOk()) status = authorize(bound.command.type());
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    if (status.isOk() && !SqlSessionCommandKinds.query(bound.command.type())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status;
  }

  StatusCode parseCommand(String sql, ParameterSet parameters, boolean typed) {
    bound.reset();
    StatusCode status = typed
        ? parseInvocation(sql, parameters)
        : SqlSessionCommandKinds.beginsSelect(sql)
            ? parser.parseQuery(sql, bound.query, bound.command)
            : parser.parse(sql, bound.command);
    if (status.isOk()) status = authorize(bound.command.type());
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    return status;
  }

  StatusCode bindPrepared(SqlPreparedPlan plan, ParameterSet parameters, boolean queryOnly) {
    if (plan == null || parameters == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (parameters.count() != plan.parameterCount()) {
      return StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    recompile = plan.needsRecompile(session);
    bindingCatalogGeneration = recompile ? session.catalogGeneration() : 0;
    bound.reset();
    StatusCode status = plan.template().restore(bound.query, bound.command);
    if (status.isOk()) status = loadParameters(parameters, plan.parameterCount());
    if (status.isOk()) status = runtimeParameters.materialize(bound.query, bound.command);
    runtimeParameters.reset();
    if (status.isOk()) status = authorize(bound.command.type());
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    if (status.isOk() && queryOnly && !SqlSessionCommandKinds.query(bound.command.type())) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) preparedExecutions++;
    return status;
  }

  StatusCode validatePrepared(
      String sql, SqlPreparedPlan candidate, SqlRetainedBudget budget,
      SqlPreparedValidationResult result) {
    return validation.validatePrepared(sql, candidate, budget, result);
  }

  StatusCode publishPreparedBinding(SqlPreparedPlan plan) {
    return publishPreparedBinding(plan, bindingCatalogGeneration);
  }

  StatusCode publishPreparedBinding(SqlPreparedPlan plan, long catalogGeneration) {
    if (!recompile || catalogGeneration <= 0
        || !session.matchesCatalogGeneration(catalogGeneration)) {
      return StatusCode.OK;
    }
    if (!plan.publishRecompile(catalogGeneration)) return StatusCode.INVARIANT_BROKEN;
    preparedRecompiles++;
    return StatusCode.OK;
  }

  StatusCode capturePrepared(
      SqlRetainedBudget budget, SqlPreparedValidationResult result,
      long generation, long preparationGeneration) {
    long retainedBytes = SqlPreparedPlan.estimateByteCharge(bound.command, bound.query);
    if (retainedBytes <= 0) return StatusCode.FEATURE_NOT_SUPPORTED;
    StatusCode status = budget.reserveRetainedBytes(retainedBytes);
    if (!status.isOk()) return status;
    status = SqlStatementTemplate.capture(
        bound.command, bound.query, parser.templateParameterCount(), templateResult);
    if (status.isOk()) status = result.complete(
        templateResult.value(), SqlSessionCommandKinds.query(bound.command.type()),
        generation, preparationGeneration, budget, retainedBytes);
    if (!status.isOk()) {
      StatusCode release = budget.releaseRetainedBytes(retainedBytes);
      if (!release.isOk()) status = release;
    } else {
      preparedCompiles++;
    }
    templateResult.reset();
    return status;
  }

  StatusCode authorize(SqlCommandType type) {
    return authorizer == null ? StatusCode.OK
        : authorizer.authorize(SqlCommandAuthorization.requiredPermission(type));
  }

  private StatusCode loadParameters(ParameterSet parameters, int expected) {
    if (parameters == null || parameters.count() != expected) {
      return StatusCode.PARAMETER_COUNT_MISMATCH;
    }
    StatusCode status = runtimeParameters.begin(expected, parameters.textBytes());
    for (int parameter = 0; status.isOk() && parameter < expected; parameter++) {
      int descriptor = parameters.typeDescriptorAt(parameter);
      int textLength = !parameters.isNull(parameter)
              && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
          ? parameters.textLengthAt(parameter) : 0;
      status = runtimeParameters.set(
          parameter, descriptor, parameters.decimalUnscaledHighAt(parameter),
          parameters.valueAt(parameter), parameters.isNull(parameter), textLength);
      for (int index = 0; status.isOk() && index < textLength; index++) {
        status = runtimeParameters.setTextByte(
            parameter, index, parameters.textByteAt(parameter, index));
      }
    }
    return status;
  }
}
