package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds one deterministic owner-column CHECK into its durable primitive form. */
final class SqlCheckExpressionBinder {
  private final int[] stack = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final byte[] operators = new byte[SqlScalarExpression.MAXIMUM_NODES];
  private final long[] operands = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private int size;

  StatusCode bind(
      SqlCommand command, TableSchema schema, int column, int comparison) {
    SqlScalarExpression expression = command.projectionExpression(column);
    if (expression == null || !expression.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    size = 0;
    StatusCode status = StatusCode.OK;
    for (int node = 0; status.isOk() && node < expression.nodeCount(); node++) {
      status = bindNode(command, schema, column, expression, node);
    }
    if (!status.isOk() || size != 1) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    if (SqlTypeDescriptor.typeId(stack[0]) == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        && comparison != TableSchema.CHECK_EQUAL
        && comparison != TableSchema.CHECK_NOT_EQUAL) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int valueDescriptor = command.columnCheckTypeDescriptor(column);
    status = SqlTypeDescriptor.canCompare(stack[0], valueDescriptor)
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
    return status.isOk()
        ? schema.setCheck(
            column,
            comparison,
            valueDescriptor,
            command.columnCheckValue(column),
            expression.nodeCount(),
            operators,
            operands,
            descriptors)
        : status;
  }

  private StatusCode bindNode(
      SqlCommand command,
      TableSchema schema,
      int owner,
      SqlScalarExpression expression,
      int node) {
    int operator = expression.operator(node);
    if (operator == SqlScalarExpression.COLUMN) {
      return bindColumn(command, schema, owner, expression, node);
    }
    if (operator == SqlScalarExpression.LITERAL) {
      int descriptor = expression.typeDescriptor(node);
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
      return TableSchema.validFixedValue(descriptor, expression.operand(node))
          ? push(node, TableSchema.CHECK_LITERAL, expression.operand(node), descriptor)
          : StatusCode.DATATYPE_MISMATCH;
    }
    if (operator == SqlScalarExpression.ADD || operator == SqlScalarExpression.SUBTRACT) {
      return bindArithmetic(expression, node, operator);
    }
    if (operator == SqlScalarExpression.CAST) return bindCast(expression, node);
    if (operator == SqlScalarExpression.EXTRACT) return bindExtract(expression, node);
    return StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode bindColumn(
      SqlCommand command,
      TableSchema schema,
      int owner,
      SqlScalarExpression expression,
      int node) {
    int symbol = (int) expression.operand(node);
    CharSequence table = command.projectionSymbolTable(symbol);
    CharSequence name = command.projectionSymbolName(symbol);
    if (table == null || table.length() != 0 || name == null
        || schema.find(name) != owner) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (SqlTypeDescriptor.typeId(schema.typeDescriptor(owner))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return push(node, TableSchema.CHECK_COLUMN, owner, schema.typeDescriptor(owner));
  }

  private StatusCode bindArithmetic(
      SqlScalarExpression expression, int node, int operator) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int right = stack[--size];
    int left = stack[size - 1];
    int descriptor = SqlRowExpressionTypes.dateArithmeticDescriptor(
        operator, left, right);
    if (descriptor == SqlRowExpressionTypes.UNSUPPORTED_NUMERIC) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    stack[size - 1] = descriptor;
    return publish(
        node,
        operator == SqlScalarExpression.ADD
            ? TableSchema.CHECK_ADD : TableSchema.CHECK_SUBTRACT,
        0,
        descriptor);
  }

  private StatusCode bindCast(SqlScalarExpression expression, int node) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int source = stack[size - 1];
    int target = expression.typeDescriptor(node);
    int descriptor = SqlCheckExpressionTypes.castDescriptor(source, target);
    if (descriptor <= 0) return descriptor == 0
        ? StatusCode.DATATYPE_MISMATCH : StatusCode.FEATURE_NOT_SUPPORTED;
    stack[size - 1] = descriptor;
    return publish(node, TableSchema.CHECK_CAST, 0, descriptor);
  }

  private StatusCode bindExtract(SqlScalarExpression expression, int node) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int descriptor = SqlRowExpressionTypes.unaryDescriptor(
        SqlScalarExpression.EXTRACT,
        stack[size - 1],
        expression.typeDescriptor(node),
        expression.operand(node));
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    stack[size - 1] = descriptor;
    return publish(
        node, TableSchema.CHECK_EXTRACT, expression.operand(node), descriptor);
  }

  private StatusCode push(int node, int operator, long operand, int descriptor) {
    if (size >= stack.length) return StatusCode.RESOURCE_EXHAUSTED;
    stack[size++] = descriptor;
    return publish(node, operator, operand, descriptor);
  }

  private StatusCode publish(int node, int operator, long operand, int descriptor) {
    operators[node] = (byte) operator;
    operands[node] = operand;
    descriptors[node] = descriptor;
    return StatusCode.OK;
  }

}
