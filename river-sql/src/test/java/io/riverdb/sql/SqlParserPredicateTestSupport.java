package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class SqlParserPredicateTestSupport {
  static void assertBooleanNotOverLeaf(
      SqlBooleanPredicateProgram predicates, int leaf) {
    assertFalse(predicates.leafNegated(leaf));
    int root = predicates.root();
    assertEquals(SqlBooleanPredicateProgram.BOOLEAN_NOT, predicates.booleanOperator(root));
    int leafNode = predicates.booleanLeft(root);
    assertEquals(SqlBooleanPredicateProgram.BOOLEAN_LEAF,
        predicates.booleanOperator(leafNode));
    assertEquals(leaf, predicates.booleanLeft(leafNode));
  }

  static void assertPostfix(
      SqlScalarExpression expression, int... operators) {
    assertEquals(operators.length, expression.nodeCount());
    for (int index = 0; index < operators.length; index++) {
      assertEquals(operators[index], expression.operator(index));
    }
  }

  static SqlScalarExpression predicateExpression(
      SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    int count = predicates.programNodeCount(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
    if (count == 1 && predicates.programOperator(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0)
        == SqlScalarExpression.COLUMN) return null;
    SqlScalarExpression expression = new SqlScalarExpression();
    for (int node = 0; node < count; node++) {
      expression.append(
          predicates.programOperator(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node),
          predicates.programOperand(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node),
          predicates.programDescriptor(leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node));
    }
    expression.finish(predicates.programDescriptor(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, count - 1));
    return expression;
  }

  static SqlComparison predicateComparison(SqlCommand command, int leaf) {
    return command.wherePredicates().comparison(leaf);
  }

  static int predicateDescriptor(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    int program = predicates.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_BETWEEN
        ? SqlBooleanPredicateProgram.PROGRAM_LOWER
        : SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int count = predicates.programNodeCount(leaf, program);
    if (count > 0) return predicates.programDescriptor(leaf, program, count - 1);
    for (int member = 0; member < predicates.leafMemberCount(leaf); member++) {
      int descriptor = predicates.memberDescriptor(leaf, member);
      if (descriptor != 0) return descriptor;
    }
    return 0;
  }

  static long predicateValue(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.wherePredicates(), leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT);
  }

  static long predicateLower(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.wherePredicates(), leaf, SqlBooleanPredicateProgram.PROGRAM_LOWER);
  }

  static long predicateUpper(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.wherePredicates(), leaf, SqlBooleanPredicateProgram.PROGRAM_UPPER);
  }

  static long predicateProgramValue(
      SqlBooleanPredicateProgram predicates, int leaf, int program) {
    int count = predicates.programNodeCount(leaf, program);
    return count == 0 ? 0 : predicates.programOperand(leaf, program, count - 1);
  }

  static int predicateProgramDescriptor(
      SqlBooleanPredicateProgram predicates, int leaf, int program) {
    int count = predicates.programNodeCount(leaf, program);
    return count == 0 ? 0 : predicates.programDescriptor(leaf, program, count - 1);
  }

  static SqlIdentifier predicateColumnName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0);
    return command.predicateSymbolName(symbol);
  }

  static SqlIdentifier predicateTableName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, 0);
    return command.predicateSymbolTable(symbol);
  }

  static SqlIdentifier predicateValueTableName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0);
    return command.predicateSymbolTable(symbol);
  }

  static SqlIdentifier predicateValueColumnName(SqlCommand command, int leaf) {
    int symbol = (int) command.wherePredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0);
    return command.predicateSymbolName(symbol);
  }

  static boolean isColumnPredicate(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    return predicates.programNodeCount(
        leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT) == 1
        && predicates.programOperator(
            leaf, SqlBooleanPredicateProgram.PROGRAM_RIGHT, 0)
        == SqlScalarExpression.COLUMN;
  }

  static int membershipCount(SqlCommand command, int leaf) {
    return command.wherePredicates().leafMemberCount(leaf);
  }

  static long membershipValue(SqlCommand command, int leaf, int member) {
    return command.wherePredicates().memberValue(leaf, member);
  }

  static boolean membershipHasNull(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.wherePredicates();
    for (int member = 0; member < predicates.leafMemberCount(leaf); member++) {
      if (predicates.memberNull(leaf, member)) return true;
    }
    return false;
  }

  static SqlComparison havingComparison(SqlCommand command, int leaf) {
    return command.booleanHavingPredicates().comparison(leaf);
  }

  static long havingValue(SqlCommand command, int leaf) {
    return predicateProgramValue(
        command.booleanHavingPredicates(),
        leaf,
        SqlBooleanPredicateProgram.PROGRAM_RIGHT);
  }

  static long havingOperand(SqlCommand command, int leaf, int node) {
    return command.booleanHavingPredicates().programOperand(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT, node);
  }

  static int havingNodeCount(SqlCommand command, int leaf) {
    return command.booleanHavingPredicates().programNodeCount(
        leaf, SqlBooleanPredicateProgram.PROGRAM_LEFT);
  }

  static int havingMemberCount(SqlCommand command, int leaf) {
    return command.booleanHavingPredicates().leafMemberCount(leaf);
  }

  static boolean havingMembershipHasNull(SqlCommand command, int leaf) {
    SqlBooleanPredicateProgram predicates = command.booleanHavingPredicates();
    for (int member = 0; member < predicates.leafMemberCount(leaf); member++) {
      if (predicates.memberNull(leaf, member)) return true;
    }
    return false;
  }

  static void assertHavingPostfix(
      SqlCommand command, int predicate, int... expected) {
    assertEquals(expected.length, havingNodeCount(command, predicate));
    for (int node = 0; node < expected.length; node++) {
      assertEquals(
          expected[node],
          command.booleanHavingPredicates().programOperator(
              predicate, SqlBooleanPredicateProgram.PROGRAM_LEFT, node));
    }
  }

  static void assertMutationPostfix(
      SqlCommand command, int expression, int... operators) {
    assertEquals(operators.length, command.mutationExpressionNodeCount(expression));
    for (int index = 0; index < operators.length; index++) {
      assertEquals(
          operators[index],
          command.mutationExpressionOperator(expression, index));
    }
  }

  static void assertSubqueryEdge(
      SqlQuery query,
      int edge,
      int kind,
      int parent,
      int leaf,
      int child,
      int leafTest) {
    assertEquals(kind, query.edgeKind(edge));
    assertEquals(parent, query.edgeParent(edge));
    assertEquals(leaf, query.edgeLeaf(edge));
    assertEquals(child, query.edgeChild(edge));
    assertEquals(parent, query.blockParent(child));
    assertEquals(query.blockDepth(parent) + 1, query.blockDepth(child));
    SqlBooleanPredicateProgram predicates = query.block(parent).wherePredicates();
    assertEquals(leafTest, predicates.leafTest(leaf));
    assertEquals(edge, predicates.subqueryEdge(leaf));
  }
}
