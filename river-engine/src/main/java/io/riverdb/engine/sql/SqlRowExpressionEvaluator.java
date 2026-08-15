package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates one bound temporal row-expression program into caller-owned storage. */
final class SqlRowExpressionEvaluator {
  private final long[] values = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] nulls = new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private final char[] text = new char[510];
  private final TextView textView = new TextView(text);
  private final LocalTemporal.Value temporalValue = new LocalTemporal.Value();
  private final LocalTemporalCast.TextResult textResult = new LocalTemporalCast.TextResult();
  private final SqlTemporalContext.LongResult longResult = new SqlTemporalContext.LongResult();
  private final SqlExactExpressionEvaluator exact = new SqlExactExpressionEvaluator();
  private final SqlExpressionEvaluator columns;
  private final SqlTemporalContext temporal;
  private int size;
  private int textLength;
  private long aggregateValue;
  private boolean aggregateNull;
  private long[] aggregateValues;
  private boolean[] aggregateNulls;
  private long groupValue;
  private boolean groupNull;

  SqlRowExpressionEvaluator(
      SqlExpressionEvaluator columnReader, SqlTemporalContext temporalContext) {
    columns = columnReader;
    temporal = temporalContext;
  }

  StatusCode evaluate(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int projection,
      SqlTemporalZonePlan zone,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      SqlProjectedRow result) {
    StatusCode status = evaluate(
        command, programs, projection, zone, primaryKey, source, definition);
    if (!status.isOk()) return status;
    if (nulls[0]) result.setNull(projection); else result.setValue(projection, values[0]);
    if (!nulls[0]
        && SqlTypeDescriptor.typeId(descriptors[0]) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && programs.rawColumn(projection) < 0) {
      result.setText(projection, text, textLength);
    }
    return StatusCode.OK;
  }

  StatusCode evaluateMutation(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int expression,
      SqlTemporalZonePlan zone,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition) {
    size = 0;
    textLength = 0;
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.mutationNodeCount(expression);
        node++) {
      int operator = programs.mutationOperator(expression, node);
      long operand = programs.mutationOperand(expression, node);
      int descriptor = programs.mutationDescriptor(expression, node);
      status = leaf(operator)
          ? leaf(
              command,
              operator,
              operand,
              descriptor,
              primaryKey,
              source,
              definition)
          : binaryOperator(operator)
              ? binary(operator, descriptor)
              : unary(operator, operand, descriptor, zone);
    }
    return !status.isOk() || size == 1
        ? status : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode evaluateBlock(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int projection,
      SqlTemporalZonePlan zone,
      SqlBlockRow source,
      SqlBlockRow result) {
    size = 0;
    textLength = 0;
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(projection); node++) {
      int operator = programs.operator(projection, node);
      status = operator == SqlScalarExpression.COLUMN
          ? blockColumn(source, (int) programs.operand(projection, node),
              programs.descriptor(projection, node))
          : leaf(operator)
              ? leaf(
                  command,
                  operator,
                  programs.operand(projection, node),
                  programs.descriptor(projection, node),
                  0,
                  null,
                  null)
              : binaryOperator(operator)
                  ? binary(operator, programs.descriptor(projection, node))
                  : unary(
                      operator,
                      programs.operand(projection, node),
                      programs.descriptor(projection, node),
                      zone);
    }
    if (!status.isOk() || size != 1) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    if (nulls[0]) result.setNull(projection); else result.setValue(projection, values[0]);
    if (!nulls[0]
        && SqlTypeDescriptor.typeId(descriptors[0]) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      result.setText(projection, text, 0, textLength);
    }
    return StatusCode.OK;
  }

  StatusCode evaluateBlock(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int projection,
      SqlTemporalZonePlan zone,
      SqlBlockRow source) {
    size = 0;
    textLength = 0;
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(projection); node++) {
      int operator = programs.operator(projection, node);
      status = operator == SqlScalarExpression.COLUMN
          ? blockColumn(source, (int) programs.operand(projection, node),
              programs.descriptor(projection, node))
          : leaf(operator)
              ? leaf(command, operator, programs.operand(projection, node),
                  programs.descriptor(projection, node), 0, null, null)
              : binaryOperator(operator)
                  ? binary(operator, programs.descriptor(projection, node))
                  : unary(operator, programs.operand(projection, node),
                      programs.descriptor(projection, node), zone);
    }
    return !status.isOk() || size == 1
        ? status : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode blockColumn(SqlBlockRow source, int column, int descriptor) {
    if (source == null || column < 0 || column >= source.count()
        || size >= values.length) return StatusCode.INVALID_EXTERNAL_INPUT;
    nulls[size] = source.nullValue(column);
    values[size] = source.value(column);
    descriptors[size] = descriptor;
    if (!nulls[size]
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      textLength = source.textLength(column);
      if (textLength > text.length) return StatusCode.RESOURCE_EXHAUSTED;
      for (int index = 0; index < textLength; index++) {
        text[index] = source.textCharacter(column, index);
      }
    }
    size++;
    return StatusCode.OK;
  }

  StatusCode evaluateAggregate(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int projection,
      SqlTemporalZonePlan zone,
      long value,
      boolean nullValue) {
    aggregateValue = value;
    aggregateNull = nullValue;
    return evaluate(command, programs, projection, zone, 0, null, null);
  }

  StatusCode evaluateHaving(
      SqlCommand command,
      SqlBoundHavingPrograms programs,
      int predicate,
      SqlTemporalZonePlan zone,
      long[] finalizedValues,
      boolean[] finalizedNulls,
      long finalizedGroup,
      boolean finalizedGroupNull) {
    aggregateValues = finalizedValues;
    aggregateNulls = finalizedNulls;
    groupValue = finalizedGroup;
    groupNull = finalizedGroupNull;
    size = 0;
    textLength = 0;
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(predicate); node++) {
      int operator = programs.operator(predicate, node);
      status = leaf(operator)
          ? havingLeaf(
              command,
              operator,
              programs.operand(predicate, node),
              programs.descriptor(predicate, node))
          : binaryOperator(operator)
              ? binary(operator, programs.descriptor(predicate, node))
              : unary(
                  operator,
                  programs.operand(predicate, node),
                  programs.descriptor(predicate, node),
                  zone);
    }
    aggregateValues = null;
    aggregateNulls = null;
    return !status.isOk() || size == 1
        ? status : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode havingLeaf(
      SqlCommand command, int operator, long operand, int descriptor) {
    if (operator == SqlScalarExpression.AGGREGATE_VALUE) {
      int invocation = (int) operand;
      if (aggregateValues == null || invocation < 0
          || invocation >= aggregateValues.length) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return pushSeed(
          aggregateValues[invocation], descriptor, aggregateNulls[invocation]);
    }
    if (operator == SqlScalarExpression.GROUP_VALUE) {
      return pushSeed(groupValue, descriptor, groupNull);
    }
    return leaf(command, operator, operand, descriptor, 0, null, null);
  }

  private StatusCode pushSeed(long value, int descriptor, boolean nullValue) {
    if (size >= values.length) return StatusCode.RESOURCE_EXHAUSTED;
    values[size] = value;
    descriptors[size] = descriptor;
    nulls[size++] = nullValue;
    return StatusCode.OK;
  }

  StatusCode evaluate(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int projection,
      SqlTemporalZonePlan zone,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition) {
    size = 0;
    textLength = 0;
    StatusCode status = StatusCode.OK;
    for (int node = 0; status.isOk() && node < programs.nodeCount(projection); node++) {
      int operator = programs.operator(projection, node);
      status = leaf(operator)
          ? leaf(command, programs, projection, node, primaryKey, source, definition)
          : binaryOperator(operator)
              ? binary(operator, programs.descriptor(projection, node))
              : unary(
                  operator,
                  programs.operand(projection, node),
                  programs.descriptor(projection, node),
                  zone);
    }
    if (!status.isOk() || size != 1) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    return StatusCode.OK;
  }

  boolean resultNull() { return nulls[0]; }
  long resultValue() { return values[0]; }
  int resultDescriptor() { return descriptors[0]; }
  int resultTextLength() { return textLength; }
  char resultTextCharacter(int index) { return text[index]; }

  void seedResult(long value, int descriptor, boolean nullValue) {
    values[0] = value;
    descriptors[0] = descriptor;
    nulls[0] = nullValue;
    size = 1;
    textLength = 0;
  }

  private StatusCode leaf(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int projection,
      int node,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition) {
    int operator = programs.operator(projection, node);
    long operand = programs.operand(projection, node);
    int descriptor = programs.descriptor(projection, node);
    return leaf(
        command, operator, operand, descriptor, primaryKey, source, definition);
  }

  private StatusCode leaf(
      SqlCommand command,
      int operator,
      long operand,
      int descriptor,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition) {
    if (size >= values.length) return StatusCode.RESOURCE_EXHAUSTED;
    nulls[size] = operator == SqlScalarExpression.NULL;
    values[size] = 0;
    descriptors[size] = descriptor;
    StatusCode status = StatusCode.OK;
    if (operator == SqlScalarExpression.AGGREGATE_VALUE) {
      nulls[size] = aggregateNull;
      values[size] = aggregateValue;
    } else if (operator == SqlScalarExpression.COLUMN) {
      int column = (int) operand;
      nulls[size] = columns.isNull(source, definition, column);
      if (!nulls[size]) values[size] = columns.readColumn(primaryKey, source, column);
    } else if (operator == SqlScalarExpression.LITERAL) {
      values[size] = operand;
    } else if (operator != SqlScalarExpression.NULL) {
      status = temporal.currentValue(operator, descriptor, longResult);
      values[size] = longResult.value;
    }
    if (status.isOk()
        && !nulls[size]
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      status = operator == SqlScalarExpression.LITERAL
          ? loadLiteralText(command, operand) : loadRowText(source, values[size]);
    }
    size++;
    return status;
  }

  private StatusCode loadLiteralText(SqlCommand command, long handle) {
    int length = command.textByteLength(handle);
    if (length < 0 || length > text.length) return StatusCode.RESOURCE_EXHAUSTED;
    for (int index = 0; index < length; index++) {
      int character = Byte.toUnsignedInt(command.textByteAt(handle, index));
      if (character > 0x7f) return StatusCode.INVALID_DATETIME_FORMAT;
      text[index] = (char) character;
    }
    textLength = length;
    return StatusCode.OK;
  }

  private StatusCode loadRowText(HeapRowResult source, long handle) {
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    if (length < 0 || length > text.length) return StatusCode.RESOURCE_EXHAUSTED;
    textLength = Utf8RowText.decode(source, offset, length, text);
    return StatusCode.OK;
  }

  private StatusCode unary(
      int operator, long operand, int target, SqlTemporalZonePlan zone) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = size - 1;
    if (nulls[slot]) {
      descriptors[slot] = target;
      return StatusCode.OK;
    }
    int source = descriptors[slot];
    StatusCode status = switch (operator) {
      case SqlScalarExpression.NEGATE,
          SqlScalarExpression.ABSOLUTE,
          SqlScalarExpression.CEILING,
          SqlScalarExpression.FLOOR,
          SqlScalarExpression.ROUND,
          SqlScalarExpression.TRUNCATE ->
          exact.unary(operator, values[slot], source, target);
      case SqlScalarExpression.CAST -> cast(values[slot], source, target);
      case SqlScalarExpression.AT_TIME_ZONE -> temporal.atTimeZone(
          values[slot], source, zone, longResult);
      case SqlScalarExpression.EXTRACT -> extract(values[slot], source, operand);
      default -> StatusCode.FEATURE_NOT_SUPPORTED;
    };
    if (status.isOk()) {
      values[slot] = operator == SqlScalarExpression.EXTRACT
          ? temporalValue.value : SqlExactExpressionEvaluator.unaryOperator(operator)
              ? exact.value() : longResult.value;
      descriptors[slot] = target;
    }
    return status;
  }

  private StatusCode cast(long value, int source, int target) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (SqlTypeDescriptor.comparisonFamily(source)
            == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC
        && SqlTypeDescriptor.comparisonFamily(target)
            == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) {
      StatusCode status = exact.cast(value, source, target);
      longResult.value = exact.value();
      return status;
    }
    if (targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = temporal.formatTemporal(
          value, source, target, text, textResult);
      textLength = textResult.length;
      longResult.value = 0;
      return status;
    }
    if (sourceType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      textView.length = textLength;
      StatusCode status = LocalTemporalCast.parseText(
          textView, 0, textLength, target, temporalValue);
      longResult.value = temporalValue.value;
      return status;
    }
    return temporal.castTemporal(value, source, target, longResult);
  }

  private StatusCode extract(long value, int source, long field) {
    return field < Integer.MIN_VALUE || field > Integer.MAX_VALUE
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : LocalTemporal.extract(value, source, (int) field, temporalValue);
  }

  private StatusCode binary(int operator, int target) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int right = --size;
    int left = size - 1;
    if (nulls[left] || nulls[right]) {
      nulls[left] = true;
      descriptors[left] = target;
      return StatusCode.OK;
    }
    int leftDescriptor = descriptors[left];
    int rightDescriptor = descriptors[right];
    int rightType = SqlTypeDescriptor.typeId(rightDescriptor);
    boolean date = SqlTypeDescriptor.typeId(leftDescriptor)
        == SqlTypeDescriptor.TYPE_ID_DATE;
    StatusCode status = date
        ? operator == SqlScalarExpression.ADD
            ? LocalTemporal.addDateDays(values[left], values[right], temporalValue)
            : rightType == SqlTypeDescriptor.TYPE_ID_DATE
                ? LocalTemporal.subtractDates(
                    values[left], values[right], temporalValue)
                : LocalTemporal.subtractDateDays(
                    values[left], values[right], temporalValue)
        : exact.binary(
            operator,
            values[left],
            leftDescriptor,
            values[right],
            rightDescriptor,
            target);
    if (status.isOk()) {
      values[left] = date ? temporalValue.value : exact.value();
      descriptors[left] = target;
    }
    return status;
  }

  private static boolean leaf(int operator) {
    return operator == SqlScalarExpression.COLUMN
        || operator == SqlScalarExpression.AGGREGATE_VALUE
        || operator == SqlScalarExpression.GROUP_VALUE
        || operator == SqlScalarExpression.LITERAL
        || operator == SqlScalarExpression.NULL
        || operator >= SqlScalarExpression.CURRENT_DATE
            && operator <= SqlScalarExpression.LOCALTIMESTAMP;
  }

  private static boolean binaryOperator(int operator) {
    return operator >= SqlScalarExpression.ADD
        && operator <= SqlScalarExpression.REMAINDER;
  }

  private static final class TextView implements CharSequence {
    private final char[] characters;
    private int length;

    TextView(char[] value) {
      characters = value;
    }

    @Override public int length() { return length; }
    @Override public char charAt(int index) { return characters[index]; }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
