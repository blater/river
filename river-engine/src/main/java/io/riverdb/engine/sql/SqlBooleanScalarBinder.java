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
  private int size;

  StatusCode bind(
      SqlCommand command,
      SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram target,
      BoundSqlStatement statement,
      SqlBlockSchema schema,
      int leaf,
      int program,
      boolean join,
      boolean having,
      BoundSqlQuery nested,
      int nestedBlock) {
    target.beginProgram(leaf, program);
    size = 0;
    for (int node = 0; node < source.programNodeCount(leaf, program); node++) {
      StatusCode status = bindNode(
          command, source, target, statement, schema,
          leaf, program, node, join, having, nested, nestedBlock);
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
      SqlBlockSchema schema,
      int leaf,
      int program,
      int node,
      boolean join,
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
          command, target, statement, schema, leaf, program,
          operator, operand, join);
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
    int scope = qualifier.length() == 0 ? block : nestedScope(query, block, qualifier);
    BoundSqlQuery.Block source = query.block(scope);
    int column = source == null || source.table() == null
        ? -1 : source.table().findColumn(name);
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (scope != block) query.markCorrelated(block, scope);
    return push(
        target,
        leaf,
        program,
        operator,
        column,
        source.table().typeDescriptor(column),
        false,
        scope);
  }

  private static int nestedScope(
      BoundSqlQuery query, int block, CharSequence qualifier) {
    int scope = block;
    while (scope >= 0) {
      BoundSqlQuery.Block source = query.block(scope);
      if (SqlBindingNames.same(qualifier, source.tableName())
          || source.tableAlias().length() > 0
              && SqlBindingNames.same(qualifier, source.tableAlias())) return scope;
      scope = query.blockParent(scope);
    }
    return -1;
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
      SqlBlockSchema schema,
      int leaf,
      int program,
      int operator,
      long operand,
      boolean join) {
    int scope = join ? joinScope(command, (int) operand)
        : SqlBoundBooleanPredicateProgram.SCOPE_LEFT;
    int column = join
        ? resolveJoin(command, statement, (int) operand, scope)
        : resolve(command, statement, schema, (int) operand);
    if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int descriptor = join
        ? (scope == SqlBoundBooleanPredicateProgram.SCOPE_LEFT
            ? statement.table : statement.joinTable).typeDescriptor(column)
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

  private static int joinScope(SqlCommand command, int symbol) {
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (qualifier == null) return -1;
    if (SqlBindingNames.matchesTable(command, qualifier)) {
      return SqlBoundBooleanPredicateProgram.SCOPE_LEFT;
    }
    return SqlBindingNames.matchesJoinTable(command, qualifier)
        ? SqlBoundBooleanPredicateProgram.SCOPE_RIGHT : -1;
  }

  private static int resolveJoin(
      SqlCommand command,
      BoundSqlStatement statement,
      int symbol,
      int scope) {
    if (scope < 0) return -1;
    CharSequence name = command.projectionSymbolName(symbol);
    if (name == null) return -1;
    return (scope == SqlBoundBooleanPredicateProgram.SCOPE_LEFT
        ? statement.table : statement.joinTable).findColumn(name);
  }
}
