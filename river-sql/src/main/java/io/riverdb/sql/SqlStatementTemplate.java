package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Compact immutable syntax for the root point-query and DML prepared-plan slice. */
public final class SqlStatementTemplate {
  private final SqlCommandType type;
  private final String table;
  private final String alias;
  private final SqlTemplateQueryBlocks queryBlocks;
  private final SqlTemplateJoinShape joinShape;
  private final SqlTemplateQueryShape queryShape;
  private final SqlTemplateMutationShape mutationShape;
  private final byte[] text;
  private final long key;
  private final long value;
  private final long lower;
  private final long upper;
  private final long rowLimit;
  private final boolean bounded;
  private final boolean selectAll;
  private final boolean selectForUpdate;
  private final int parameterCount;

  private SqlStatementTemplate(SqlCommand source) { this(source, null); }

  SqlStatementTemplate(SqlCommand source, SqlQuery query) {
    type = source.type;
    table = SqlTemplateStrings.copy(source.tableName);
    alias = SqlTemplateStrings.copy(source.tableAlias);
    queryBlocks = new SqlTemplateQueryBlocks(query);
    joinShape = new SqlTemplateJoinShape(source.joinChain);
    queryShape = new SqlTemplateQueryShape(source);
    mutationShape = new SqlTemplateMutationShape(source);
    text = Arrays.copyOf(source.textBytes, source.textBytesUsed);
    key = source.key;
    value = source.value;
    lower = source.scanLowerInclusive;
    upper = source.scanUpperExclusive;
    rowLimit = source.rowLimit;
    bounded = source.boundedScan;
    selectAll = source.selectAll;
    selectForUpdate = source.selectForUpdate;
    parameterCount = parameterMaximum() + 1;
  }

  public static StatusCode capture(
      SqlCommand source, SqlQuery query, int expectedParameters, Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!supported(source, query)) return StatusCode.FEATURE_NOT_SUPPORTED;
    try {
      SqlStatementTemplate template = new SqlStatementTemplate(source, query);
      if (template.parameterCount != expectedParameters) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
      result.value = template;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public static long estimateByteCharge(SqlCommand source, SqlQuery query) {
    return source == null || query == null || !supported(source, query)
        ? 0 : SqlTemplateSizer.statement(source, query);
  }

  public StatusCode restore(SqlQuery query, SqlCommand target) {
    if (query == null || target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    query.reset();
    StatusCode status = restoreCommand(target);
    if (status.isOk()) status = queryBlocks.restore(query);
    if (!status.isOk()) query.reset();
    return status;
  }

  StatusCode restoreCommand(SqlCommand target) {
    target.reset();
    target.type = type;
    target.tableName.copyFrom(table);
    target.tableAlias.copyFrom(alias);
    System.arraycopy(text, 0, target.textBytes, 0, text.length);
    target.textBytesUsed = text.length;
    StatusCode status = joinShape.restore(target);
    if (status.isOk()) status = queryShape.restore(target);
    if (status.isOk()) status = mutationShape.restore(target);
    if (!status.isOk()) return failed(target, status);
    target.key = key;
    target.value = value;
    target.scanLowerInclusive = lower;
    target.scanUpperExclusive = upper;
    target.rowLimit = rowLimit;
    target.boundedScan = bounded;
    target.selectAll = selectAll;
    target.selectForUpdate = selectForUpdate;
    status = target.finish();
    return status.isOk() ? status : failed(target, status);
  }

  public SqlCommandType type() { return type; }
  public int parameterCount() { return parameterCount; }

  public long byteCharge() {
    long bytes = 128L;
    bytes = SqlTemplateRetainedSize.add(bytes,
        SqlTemplateRetainedSize.string(table), SqlTemplateRetainedSize.string(alias));
    bytes = SqlTemplateRetainedSize.add(
        bytes, SqlTemplateRetainedSize.array(text.length, Byte.BYTES));
    bytes = SqlTemplateRetainedSize.add(bytes, joinShape.byteCharge());
    bytes = SqlTemplateRetainedSize.add(bytes, queryShape.byteCharge());
    bytes = SqlTemplateRetainedSize.add(bytes, mutationShape.byteCharge());
    return SqlTemplateRetainedSize.add(bytes, queryBlocks.byteCharge());
  }

  private static boolean supported(SqlCommand source, SqlQuery query) {
    if (source == null || !source.isAvailable() || query == null
        || query.hasNestedTopology() || query.hasSetExpression()
        || query.blockCount() > 0 && !query.isBlockPipeline()) return false;
    return switch (source.type) {
      case INSERT, UPDATE, DELETE, SELECT, SCAN, DISTINCT_SCAN, JOIN_SCAN,
          COUNT, COUNT_VALUE, COUNT_DISTINCT, SUM, AVG, MIN, MAX,
          GROUP_COUNT, GROUP_COUNT_VALUE, GROUP_COUNT_DISTINCT,
          GROUP_SUM, GROUP_AVG, GROUP_MIN, GROUP_MAX -> true;
      default -> false;
    };
  }

  int parameterMaximum() {
    return Math.max(queryBlocks.parameterMaximum(), Math.max(
        joinShape.parameterMaximum(), Math.max(
            queryShape.parameterMaximum(), mutationShape.parameterMaximum())));
  }

  private static StatusCode failed(SqlCommand target, StatusCode status) {
    target.reset();
    return status;
  }

  public static final class Result {
    private SqlStatementTemplate value;
    public void reset() { value = null; }
    public SqlStatementTemplate value() { return value; }
  }
}
