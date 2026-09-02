package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates one bound temporal row-expression program into caller-owned storage. */
final class SqlRowExpressionEvaluator {
  private final long[] values = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final long[] highs = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] nulls = new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private final SqlRowTextScratch text = new SqlRowTextScratch();
  private final LocalTemporal.Value temporalValue = new LocalTemporal.Value();
  private final LocalTemporalCast.TextResult textResult = new LocalTemporalCast.TextResult();
  private final SqlTemporalContext.LongResult longResult = new SqlTemporalContext.LongResult();
  private final SqlNumericExpressionEvaluator exact = new SqlNumericExpressionEvaluator();
  private final SqlExpressionEvaluator columns;
  private final SqlTemporalContext temporal;
  private int size;
  private long aggregateValue;
  private boolean aggregateNull;
  private long[] aggregateValues;
  private long[] aggregateHighs;
  private boolean[] aggregateNulls;
  private SqlHavingGroup havingGroup;

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
    if (nulls[0]) result.setNull(projection);
    else if (SqlTypeDescriptor.isWideDecimal(descriptors[0])) {
      result.setDecimal128(projection, highs[0], values[0]);
    } else result.setValue(projection, values[0]);
    if (!nulls[0]
        && SqlTypeDescriptor.typeId(descriptors[0]) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && programs.rawColumn(projection) < 0) {
      result.setText(projection, text.writableCharacters(), text.length());
    }
    return result.status();
  }

  StatusCode evaluateOperand(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      SqlTemporalZonePlan zone,
      long key,
      HeapRowResult row,
      TableDefinition table,
      SqlPredicateOperand result) {
    StatusCode status = evaluate(command, programs, 0, zone, key, row, table);
    if (status.isOk()) result.capture(this);
    reset();
    return status;
  }

  StatusCode evaluateNestedOperand(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      SqlTemporalZonePlan zone,
      SqlNestedRowProvider rows,
      SqlPredicateOperand result) {
    beginPredicateOperand();
    StatusCode status = SqlNestedProjectionNodes.evaluate(
        this, command, programs, zone, rows);
    if (status.isOk()) return finishPredicateOperand(result);
    reset();
    return status;
  }

  void beginPredicateOperand() {
    size = 0;
    text.clear();
  }

  StatusCode predicateOperandNode(
      SqlCommand command,
      int operator,
      long operand,
      int descriptor,
      SqlTemporalZonePlan zone,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      SqlBlockRow blockSource) {
    return predicateOperandNode(
        command, operator, operand >> 63, operand, descriptor, zone,
        primaryKey, source, definition, blockSource);
  }

  StatusCode predicateOperandNode(
      SqlCommand command,
      int operator,
      long operandHigh,
      long operand,
      int descriptor,
      SqlTemporalZonePlan zone,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      SqlBlockRow blockSource) {
    if (operator == SqlScalarExpression.COLUMN && blockSource != null) {
      return blockColumn(blockSource, (int) operand, descriptor);
    }
    return leaf(operator)
        ? leaf(command, operator, operandHigh, operand, descriptor,
            primaryKey, source, definition)
        : binaryOperator(operator) ? binary(operator, descriptor)
        : unary(operator, operand, descriptor, zone);
  }

  StatusCode predicateNullColumnNode(int descriptor) {
    if (size >= values.length) return StatusCode.RESOURCE_EXHAUSTED;
    values[size] = 0;
    highs[size] = 0;
    descriptors[size] = descriptor;
    nulls[size++] = true;
    return StatusCode.OK;
  }

  StatusCode predicateBlockColumnNode(
      SqlBlockRow source, int column, int descriptor) {
    return blockColumn(source, column, descriptor);
  }

  StatusCode finishPredicateOperand(SqlPredicateOperand result) {
    if (size != 1) {
      reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.capture(this);
    reset();
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
    text.clear();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.mutationNodeCount(expression);
        node++) {
      int operator = programs.mutationOperator(expression, node);
      long operand = programs.mutationOperand(expression, node);
      long operandHigh = programs.mutationOperandHigh(expression, node);
      int descriptor = programs.mutationDescriptor(expression, node);
      status = leaf(operator)
          ? leaf(
              command,
              operator,
              operandHigh,
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

  StatusCode evaluateDescriptorMutation(
      SqlCommand command,
      SqlBoundProjectionPrograms programs,
      int expression,
      SqlTemporalZonePlan zone,
      SqlValueBuffer source) {
    size = 0;
    text.clear();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.mutationNodeCount(expression); node++) {
      int operator = programs.mutationOperator(expression, node);
      long operand = programs.mutationOperand(expression, node);
      int descriptor = programs.mutationDescriptor(expression, node);
      status = operator == SqlScalarExpression.COLUMN
          ? descriptorColumn(source, (int) operand, descriptor)
          : leaf(operator)
              ? leaf(
                  command, operator, programs.mutationOperandHigh(expression, node),
                  operand, descriptor, 0, null, null)
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
    text.clear();
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
                  programs.operandHigh(projection, node),
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
    if (nulls[0]) result.setNull(projection);
    else if (SqlTypeDescriptor.isWideDecimal(descriptors[0])) {
      result.setDecimal128(projection, highs[0], values[0]);
    } else result.setValue(projection, values[0]);
    if (!nulls[0]
        && SqlTypeDescriptor.typeId(descriptors[0]) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      result.setText(projection, text.writableCharacters(), 0, text.length());
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
    text.clear();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(projection); node++) {
      int operator = programs.operator(projection, node);
      status = operator == SqlScalarExpression.COLUMN
          ? blockColumn(source, (int) programs.operand(projection, node),
              programs.descriptor(projection, node))
          : leaf(operator)
              ? leaf(
                  command, operator, programs.operandHigh(projection, node),
                  programs.operand(projection, node),
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
    highs[size] = source.highValue(column);
    descriptors[size] = descriptor;
    if (!nulls[size]
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = text.loadBlock(source, column);
      if (!status.isOk()) return status;
    }
    size++;
    return StatusCode.OK;
  }

  private StatusCode descriptorColumn(
      SqlValueBuffer source, int column, int descriptor) {
    if (source == null || column < 0 || column >= source.count()
        || size >= values.length) return StatusCode.INVALID_EXTERNAL_INPUT;
    nulls[size] = source.isNull(column);
    values[size] = source.valueAt(column);
    highs[size] = source.highValueAt(column);
    descriptors[size] = descriptor;
    if (!nulls[size]
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = text.loadValueBuffer(source, column);
      if (!status.isOk()) return status;
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

  void beginHavingPredicateOperand(
      long[] finalizedHighs,
      long[] finalizedValues,
      boolean[] finalizedNulls,
      SqlHavingGroup group) {
    beginPredicateOperand();
    aggregateHighs = finalizedHighs;
    aggregateValues = finalizedValues;
    aggregateNulls = finalizedNulls;
    havingGroup = group;
  }

  StatusCode predicateHavingOperandNode(
      SqlCommand command,
      int operator,
      long operandHigh,
      long operand,
      int descriptor,
      SqlTemporalZonePlan zone) {
    return leaf(operator)
        ? havingLeaf(command, operator, operandHigh, operand, descriptor)
        : binaryOperator(operator) ? binary(operator, descriptor)
        : unary(operator, operand, descriptor, zone);
  }

  private StatusCode havingLeaf(
      SqlCommand command,
      int operator,
      long operandHigh,
      long operand,
      int descriptor) {
    if (operator == SqlScalarExpression.AGGREGATE_VALUE) {
      int invocation = (int) operand;
      if (aggregateValues == null || invocation < 0
          || invocation >= aggregateValues.length) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return pushSeed(
          aggregateHighs[invocation], aggregateValues[invocation],
          descriptor, aggregateNulls[invocation]);
    }
    if (operator == SqlScalarExpression.GROUP_VALUE) {
      int ordinal = (int) operand;
      return havingGroup == null || !havingGroup.valid(ordinal)
          ? StatusCode.INVALID_EXTERNAL_INPUT
          : pushSeed(
              havingGroup.highValue(ordinal), havingGroup.value(ordinal),
              descriptor, havingGroup.nullValue(ordinal));
    }
    return leaf(
        command, operator, operandHigh, operand, descriptor, 0, null, null);
  }

  private StatusCode pushSeed(long value, int descriptor, boolean nullValue) {
    return pushSeed(value >> 63, value, descriptor, nullValue);
  }

  private StatusCode pushSeed(
      long high, long value, int descriptor, boolean nullValue) {
    if (size >= values.length) return StatusCode.RESOURCE_EXHAUSTED;
    highs[size] = high;
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
    text.clear();
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
  long resultHighValue() { return highs[0]; }
  int resultDescriptor() { return descriptors[0]; }
  int resultTextLength() { return text.length(); }
  char resultTextCharacter(int index) { return text.charAt(index); }

  void seedResult(long value, int descriptor, boolean nullValue) {
    seedResult(value >> 63, value, descriptor, nullValue);
  }

  void seedResult(long high, long value, int descriptor, boolean nullValue) {
    highs[0] = high;
    values[0] = value;
    descriptors[0] = descriptor;
    nulls[0] = nullValue;
    size = 1;
    text.clear();
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
    long operandHigh = programs.operandHigh(projection, node);
    long operand = programs.operand(projection, node);
    int descriptor = programs.descriptor(projection, node);
    return leaf(
        command, operator, operandHigh, operand, descriptor,
        primaryKey, source, definition);
  }

  private StatusCode leaf(
      SqlCommand command,
      int operator,
      long operandHigh,
      long operand,
      int descriptor,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition) {
    if (size >= values.length) return StatusCode.RESOURCE_EXHAUSTED;
    nulls[size] = operator == SqlScalarExpression.NULL;
    values[size] = 0;
    highs[size] = 0;
    descriptors[size] = descriptor;
    StatusCode status = StatusCode.OK;
    if (operator == SqlScalarExpression.AGGREGATE_VALUE) {
      nulls[size] = aggregateNull;
      values[size] = aggregateValue;
      highs[size] = aggregateValue >> 63;
    } else if (operator == SqlScalarExpression.COLUMN) {
      int column = (int) operand;
      nulls[size] = columns.isNull(source, definition, column);
      if (!nulls[size]) {
        values[size] = columns.readColumn(primaryKey, source, definition, column);
        highs[size] = SqlTypeDescriptor.isWideDecimal(descriptor)
            ? columns.readColumnHigh(primaryKey, source, definition, column)
            : values[size] >> 63;
      }
    } else if (operator == SqlScalarExpression.LITERAL) {
      values[size] = operand;
      highs[size] = operandHigh;
    } else if (operator != SqlScalarExpression.NULL) {
      status = temporal.currentValue(operator, descriptor, longResult);
      values[size] = longResult.value;
      highs[size] = longResult.value >> 63;
    }
    if (status.isOk()
        && !nulls[size]
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      status = operator == SqlScalarExpression.LITERAL
          ? text.loadLiteral(command, operand)
          : text.loadRow(source, definition, values[size]);
    }
    size++;
    return status;
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
          exact.unary(operator, highs[slot], values[slot], source, target, operand);
      case SqlScalarExpression.CAST -> cast(highs[slot], values[slot], source, target);
      case SqlScalarExpression.AT_TIME_ZONE -> temporal.atTimeZone(
          values[slot], source, zone, longResult);
      case SqlScalarExpression.EXTRACT -> extract(values[slot], source, operand);
      default -> StatusCode.FEATURE_NOT_SUPPORTED;
    };
    if (status.isOk()) {
      values[slot] = operator == SqlScalarExpression.EXTRACT
          ? temporalValue.value : SqlNumericExpressionEvaluator.unaryOperator(operator)
              ? exact.value() : longResult.value;
      boolean numeric = SqlNumericExpressionEvaluator.unaryOperator(operator)
          || operator == SqlScalarExpression.CAST
              && SqlNumericTypeRules.isNumeric(source)
              && SqlNumericTypeRules.isNumeric(target);
      highs[slot] = numeric
          ? exact.highValue() : values[slot] >> 63;
      descriptors[slot] = target;
    }
    return status;
  }

  private StatusCode cast(long high, long value, int source, int target) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (SqlNumericTypeRules.isNumeric(source)
        && SqlNumericTypeRules.isNumeric(target)) {
      StatusCode status = exact.cast(high, value, source, target);
      longResult.value = exact.value();
      return status;
    }
    if (targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = temporal.formatTemporal(
          value, source, target, text.writableCharacters(), textResult);
      if (status.isOk()) text.publish(textResult.length); else text.clear();
      longResult.value = 0;
      return status;
    }
    if (sourceType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = LocalTemporalCast.parseText(
          text, 0, text.length(), target, temporalValue);
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
            highs[left], values[left],
            leftDescriptor,
            highs[right], values[right],
            rightDescriptor,
            target);
    if (status.isOk()) {
      values[left] = date ? temporalValue.value : exact.value();
      highs[left] = date ? values[left] >> 63 : exact.highValue();
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

  void reset() {
    text.clear();
    size = 0;
    aggregateValues = null;
    aggregateHighs = null;
    aggregateNulls = null;
    havingGroup = null;
  }

}
