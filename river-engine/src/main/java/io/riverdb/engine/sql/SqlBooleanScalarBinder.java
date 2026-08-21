package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves one scalar postfix program embedded in a Boolean predicate. */
final class SqlBooleanScalarBinder {
  private final int[] stack =
      new int[SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES];
  private final boolean[] untyped =
      new boolean[SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES];
  private final SqlJoinRoleResolver joinRoles = new SqlJoinRoleResolver();
  private final SqlNestedColumnResolver nestedColumns = new SqlNestedColumnResolver();
  private int size;

  StatusCode bind(
      SqlCommand command,
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      SqlBoundJoinContext context,
      SqlBlockSchema schema,
      int leaf,
      int program,
      int visibleRoles,
      boolean having,
      BoundSqlQuery nested,
      int nestedBlock) {
    target.beginProgram(leaf, program);
    size = 0;
    for (int node = 0; node < source.programNodeCount(leaf, program); node++) {
      StatusCode status = bindNode(
          command, source, target, statement, context, schema,
          leaf, program, node, visibleRoles, having, nested, nestedBlock);
      if (!status.isOk()) return status;
    }
    if (size != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    boolean raw = source.programNodeCount(leaf, program) == 1
        && source.programOperator(leaf, program, 0) == SqlScalarExpression.COLUMN;
    int rawColumn = raw ? (int) target.operand(leaf, program, 0) : -1;
    target.finishProgram(leaf, program, stack[0], rawColumn);
    if (untyped[0]) target.markUnresolved(leaf, program);
    return StatusCode.OK;
  }

  private StatusCode bindNode(
      SqlCommand command,
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      SqlBoundJoinContext context,
      SqlBlockSchema schema,
      int leaf,
      int program,
      int node,
      int visibleRoles,
      boolean having,
      BoundSqlQuery nested,
      int nestedBlock) {
    int operator = source.programOperator(leaf, program, node);
    long operand = source.programOperand(leaf, program, node);
    int descriptor = source.programDescriptor(leaf, program, node);
    if (operator == SqlScalarExpression.AGGREGATE_VALUE) {
      return aggregate(
          source, target, statement, leaf, program,
          operator, operand, having);
    }
    if (operator == SqlScalarExpression.GROUP_VALUE) {
      return group(source, target, statement, leaf, program, operator, having);
    }
    if (operator == SqlScalarExpression.COLUMN) {
      if (nested != null) {
        return nestedColumn(
            command, target, nested, nestedBlock, leaf, program, operator, operand);
      }
      return column(
          command, target, statement, context, schema, leaf, program,
          operator, operand, visibleRoles);
    }
    if (operator == SqlScalarExpression.NULL) {
      int typed = descriptor == 0 ? SqlTypeDescriptor.BIGINT : descriptor;
      return push(target, leaf, program, operator, 0, typed, descriptor == 0);
    }
    if (SqlRowExpressionTypes.leaf(operator)) {
      return SqlTypeDescriptor.isValid(descriptor)
          ? push(target, leaf, program, operator, operand, descriptor, false)
          : StatusCode.DATATYPE_MISMATCH;
    }
    if (SqlRowExpressionTypes.unary(operator)
        || SqlExactExpressionEvaluator.unaryOperator(operator)) {
      return unary(target, leaf, program, operator, operand, descriptor);
    }
    return operator >= SqlScalarExpression.ADD
            && operator <= SqlScalarExpression.REMAINDER
        ? binary(target, leaf, program, operator, operand)
        : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode nestedColumn(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlQuery query,
      int block,
      int leaf,
      int program,
      int operator,
      long operand) {
    int symbol = (int) operand;
    CharSequence name = command.projectionSymbolName(symbol);
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (name == null || qualifier == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = nestedColumns.resolve(query, block, qualifier, name);
    if (!status.isOk()) return status;
    int sourceBlock = nestedColumns.block();
    int sourceRole = nestedColumns.role();
    int column = nestedColumns.column();
    if (sourceBlock != block) query.markCorrelated(block, sourceBlock);
    return push(
        target,
        leaf,
        program,
        operator,
        column,
        query.block(sourceBlock).table(sourceRole).typeDescriptor(column),
        false,
        SqlNestedRowProvider.scope(sourceBlock, sourceRole));
  }

  private StatusCode aggregate(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      int leaf,
      int program,
      int operator,
      long operand,
      boolean having) {
    int invocation = (int) operand;
    if (!having || invocation < 0 || invocation >= statement.aggregates.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int descriptor = statement.aggregates.resultDescriptor(invocation);
    if (directTextOnly(source, leaf, program, descriptor)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return push(target, leaf, program, operator, operand, descriptor, false);
  }

  private StatusCode group(
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      int leaf,
      int program,
      int operator,
      boolean having) {
    if (!having) return StatusCode.INVALID_EXTERNAL_INPUT;
    int descriptor = statement.projectedTypeDescriptors[0];
    if (directTextOnly(source, leaf, program, descriptor)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    return push(target, leaf, program, operator, 0, descriptor, false);
  }

  private static boolean directTextOnly(
      SqlBooleanPredicateProgram source, int leaf, int program, int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && source.programNodeCount(leaf, program) != 1;
  }

  private StatusCode column(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      SqlBoundJoinContext context,
      SqlBlockSchema schema,
      int leaf,
      int program,
      int operator,
      long operand,
      int visibleRoles) {
    int scope = SqlBoundBooleanPredicateProgram.SCOPE_LEFT;
    int column;
    if (visibleRoles > 0) {
      if (!joinRoles.resolve(
          command, context, (int) operand, visibleRoles)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      scope = joinRoles.role();
      column = joinRoles.column();
    } else column = resolve(command, statement, schema, (int) operand);
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int descriptor = visibleRoles > 0
        ? context.table(scope).typeDescriptor(column)
        : schema == null
            ? statement.table.typeDescriptor(column) : schema.descriptor(column);
    return push(
        target, leaf, program, operator, column, descriptor, false, scope);
  }

  private StatusCode unary(
      SqlBoundBooleanPredicateProgram target,
      int leaf,
      int program,
      int operator,
      long operand,
      int requested) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = size - 1;
    int descriptor = operator == SqlScalarExpression.CAST && untyped[slot]
        ? requested
        : SqlPostAggregateExpressionTypes.unary(
            operator, stack[slot], requested, operand);
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    stack[slot] = descriptor;
    if (operator == SqlScalarExpression.CAST) untyped[slot] = false;
    target.append(
        leaf, program, operator, operand, descriptor,
        SqlBoundBooleanPredicateProgram.SCOPE_LEFT);
    return StatusCode.OK;
  }

  private StatusCode binary(
      SqlBoundBooleanPredicateProgram target,
      int leaf,
      int program,
      int operator,
      long operand) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int right = stack[--size];
    if (untyped[size] || untyped[size - 1]) {
      if (!resolveUntypedBinary(operator, right)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      right = stack[size];
    }
    int descriptor = SqlPostAggregateExpressionTypes.binary(
        operator, stack[size - 1], right);
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    stack[size - 1] = descriptor;
    target.append(
        leaf, program, operator, operand, descriptor,
        SqlBoundBooleanPredicateProgram.SCOPE_LEFT);
    return StatusCode.OK;
  }

  private boolean resolveUntypedBinary(int operator, int right) {
    boolean rightNull = untyped[size];
    boolean leftNull = untyped[size - 1];
    if (rightNull == leftNull) return false;
    int known = rightNull ? stack[size - 1] : right;
    int family = SqlTypeDescriptor.comparisonFamily(known);
    if (family == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) {
      stack[rightNull ? size : size - 1] = known;
      untyped[size] = false;
      untyped[size - 1] = false;
      return true;
    }
    if (operator == SqlScalarExpression.ADD
        && !leftNull
        && SqlTypeDescriptor.typeId(known) == SqlTypeDescriptor.TYPE_ID_DATE) {
      stack[size] = SqlTypeDescriptor.BIGINT;
      untyped[size] = false;
      return true;
    }
    return false;
  }

  private StatusCode push(
      SqlBoundBooleanPredicateProgram target,
      int leaf,
      int program,
      int operator,
      long operand,
      int descriptor,
      boolean untypedNull) {
    return push(
        target, leaf, program, operator, operand, descriptor, untypedNull,
        SqlBoundBooleanPredicateProgram.SCOPE_LEFT);
  }

  private StatusCode push(
      SqlBoundBooleanPredicateProgram target,
      int leaf,
      int program,
      int operator,
      long operand,
      int descriptor,
      boolean untypedNull,
      int scope) {
    if (size >= stack.length) return StatusCode.RESOURCE_EXHAUSTED;
    stack[size] = descriptor;
    untyped[size++] = untypedNull;
    target.append(leaf, program, operator, operand, descriptor, scope);
    return StatusCode.OK;
  }

  private static int resolve(
      SqlCommand command,
      BoundSqlStatement statement,
      SqlBlockSchema schema,
      int symbol) {
    CharSequence name = command.projectionSymbolName(symbol);
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (name == null || qualifier == null
        || qualifier.length() > 0 && !SqlBindingNames.matchesTable(command, qualifier)) {
      return -1;
    }
    return schema == null ? statement.table.findColumn(name) : schema.find(name);
  }

}
