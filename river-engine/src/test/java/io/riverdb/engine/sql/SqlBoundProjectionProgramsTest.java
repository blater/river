package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlScalarExpression;
import org.junit.jupiter.api.Test;

final class SqlBoundProjectionProgramsTest {
  @Test
  void admitsMaximumNodesIndependentlyAcrossTwoMutationPrograms() {
    SqlBoundProjectionPrograms programs = new SqlBoundProjectionPrograms();
    assertEquals(StatusCode.OK, programs.reserveMutations(2));
    programs.beginMutations(2);

    for (int program = 0; program < 2; program++) {
      programs.beginMutation(program);
      for (int node = 0; node < SqlShapeLimits.MAX_EXPRESSION_NODES; node++) {
        programs.appendMutation(
            program, SqlScalarExpression.LITERAL, node, SqlTypeDescriptor.BIGINT);
      }
      programs.finishMutation(program, SqlTypeDescriptor.BIGINT);
    }

    assertEquals(StatusCode.OK, programs.status());
    assertEquals(SqlShapeLimits.MAX_EXPRESSION_NODES, programs.mutationNodeCount(0));
    assertEquals(SqlShapeLimits.MAX_EXPRESSION_NODES, programs.mutationNodeCount(1));
    assertEquals(SqlShapeLimits.MAX_EXPRESSION_NODES - 1,
        programs.mutationOperand(1, SqlShapeLimits.MAX_EXPRESSION_NODES - 1));
  }

  @Test
  void rejectsNodeBeyondOneProgramsIndependentLimit() {
    SqlBoundProjectionPrograms programs = new SqlBoundProjectionPrograms();
    programs.beginMutations(2);
    programs.beginMutation(0);
    for (int node = 0; node <= SqlShapeLimits.MAX_EXPRESSION_NODES; node++) {
      programs.appendMutation(
          0, SqlScalarExpression.LITERAL, node, SqlTypeDescriptor.BIGINT);
    }

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, programs.status());
    assertEquals(SqlShapeLimits.MAX_EXPRESSION_NODES, programs.mutationNodeCount(0));
    assertEquals(0, programs.mutationNodeCount(1));
  }
}
