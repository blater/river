package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionScalarOperator;
import io.riverdb.engine.sql.SqlProgramMemoryLease;
import io.riverdb.engine.sql.SqlRetainedBudget;
import org.junit.jupiter.api.Test;

final class TransactionScalarEvaluatorTest {
  private final TransactionProgramArguments invocation = new TransactionProgramArguments();
  private final TransactionProgramArguments prior = new TransactionProgramArguments();
  private final TransactionScalarEvaluator evaluator = new TransactionScalarEvaluator(
      new SqlProgramMemoryLease(new UnlimitedBudget()));

  @Test
  void convertsApproximateArithmeticToTheDeclaredExactTarget() {
    TransactionProgram program = arithmetic(
        SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.INTEGER);
    assertEquals(StatusCode.OK, invocation.setDouble(0, 1.25d));
    assertEquals(StatusCode.OK, invocation.setDouble(1, 0.75d));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertEquals(SqlTypeDescriptor.INTEGER, evaluator.descriptor());
    assertEquals(2, evaluator.low());
  }

  @Test
  void addsIntegerArguments() {
    TransactionProgram program = arithmetic(
        SqlTypeDescriptor.INTEGER, SqlTypeDescriptor.INTEGER, SqlTypeDescriptor.INTEGER);
    assertEquals(StatusCode.OK, invocation.setFixed(0, SqlTypeDescriptor.INTEGER, 20));
    assertEquals(StatusCode.OK, invocation.setFixed(1, SqlTypeDescriptor.INTEGER, 5));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertEquals(25, evaluator.low());
  }

  @Test
  void subtractsIntegerArguments() {
    TransactionProgram program = arithmetic(
        SqlTypeDescriptor.INTEGER, SqlTypeDescriptor.INTEGER,
        SqlTypeDescriptor.INTEGER, TransactionScalarOperator.SUBTRACT);
    assertEquals(StatusCode.OK, invocation.setFixed(0, SqlTypeDescriptor.INTEGER, 20));
    assertEquals(StatusCode.OK, invocation.setFixed(1, SqlTypeDescriptor.INTEGER, 5));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertEquals(15, evaluator.low());
  }

  @Test
  void rejectsRealOverflowBeforePublishingInvalidBits() {
    TransactionProgram program = arithmetic(
        SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.REAL);
    assertEquals(StatusCode.OK, invocation.setDouble(0, Double.MAX_VALUE));
    assertEquals(StatusCode.OK, invocation.setDouble(1, Double.MAX_VALUE));

    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        evaluator.evaluate(program, 0, invocation, prior));
  }

  @Test
  void varcharCastPublishesTheTargetDescriptor() {
    int source = SqlTypeDescriptor.varchar(8);
    int target = SqlTypeDescriptor.varchar(32);
    TransactionProgram program = unary(source, target, TransactionScalarOperator.CAST);
    assertEquals(StatusCode.OK, invocation.setText(0, source, "river"));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertEquals(target, evaluator.descriptor());
    assertEquals(5, evaluator.textLength());
  }

  @Test
  void varcharCastMeasuresSupplementaryCharactersAsUnicodeScalars() {
    int source = SqlTypeDescriptor.varchar(2);
    int target = SqlTypeDescriptor.varchar(1);
    TransactionProgram program = unary(source, target, TransactionScalarOperator.CAST);
    assertEquals(StatusCode.OK, invocation.setText(0, source, "\ud83d\ude80"));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertEquals(target, evaluator.descriptor());
    assertEquals(2, evaluator.textLength());
    assertEquals('\ud83d', evaluator.textCharacter(0));
    assertEquals('\ude80', evaluator.textCharacter(1));
  }

  @Test
  void rejectsAnArgumentSlotWhoseRequiredCountCannotBeRepresented() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        program.argument(Integer.MAX_VALUE, SqlTypeDescriptor.INTEGER));
  }

  @Test
  void selectsTheTrueBranchAfterNestedIntegerArithmetic() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    argument(program, 0);
    argument(program, 1);
    argument(program, 2);
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.ADD, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.GREATER_OR_EQUAL, SqlTypeDescriptor.BOOLEAN));
    argument(program, 0);
    argument(program, 1);
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.SUBTRACT, SqlTypeDescriptor.INTEGER));
    argument(program, 0);
    argument(program, 3);
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.ADD, SqlTypeDescriptor.INTEGER));
    argument(program, 1);
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.SUBTRACT, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.SELECT, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    assertEquals(StatusCode.OK, invocation.setFixed(0, SqlTypeDescriptor.INTEGER, 20));
    assertEquals(StatusCode.OK, invocation.setFixed(1, SqlTypeDescriptor.INTEGER, 5));
    assertEquals(StatusCode.OK, invocation.setFixed(2, SqlTypeDescriptor.INTEGER, 10));
    assertEquals(StatusCode.OK, invocation.setFixed(3, SqlTypeDescriptor.INTEGER, 91));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertEquals(15, evaluator.low());
  }

  @Test
  void selectsTheRequestedFixedValue() {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(0, SqlTypeDescriptor.BOOLEAN));
    argument(program, 1);
    argument(program, 2);
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.SELECT, SqlTypeDescriptor.INTEGER));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    assertEquals(StatusCode.OK, invocation.setFixed(0, SqlTypeDescriptor.BOOLEAN, 1));
    assertEquals(StatusCode.OK, invocation.setFixed(1, SqlTypeDescriptor.INTEGER, 15));
    assertEquals(StatusCode.OK, invocation.setFixed(2, SqlTypeDescriptor.INTEGER, 106));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertEquals(15, evaluator.low());
  }

  @Test
  void selectsEitherExactCapacityTextBranchWithoutCopyingTheArena() {
    int text = SqlTypeDescriptor.varchar(8);
    TransactionProgram program = select(text);
    assertEquals(StatusCode.OK, invocation.setFixed(0, SqlTypeDescriptor.BOOLEAN, 1));
    assertEquals(StatusCode.OK, invocation.setText(1, text, "selected"));
    assertEquals(StatusCode.OK, invocation.setText(2, text, "discard!"));

    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertText("selected");

    invocation.reset();
    assertEquals(StatusCode.OK, invocation.setFixed(0, SqlTypeDescriptor.BOOLEAN, 0));
    assertEquals(StatusCode.OK, invocation.setText(1, text, "discard!"));
    assertEquals(StatusCode.OK, invocation.setText(2, text, "selected"));
    assertEquals(StatusCode.OK, evaluator.evaluate(program, 0, invocation, prior));
    assertText("selected");
  }

  private static TransactionProgram arithmetic(int left, int right, int target) {
    return arithmetic(left, right, target, TransactionScalarOperator.ADD);
  }

  private static TransactionProgram arithmetic(int left, int right, int target, int operator) {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(0, left));
    assertEquals(StatusCode.OK, program.argument(1, right));
    assertEquals(StatusCode.OK, program.operator(operator, target));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    return program;
  }

  private static void argument(TransactionProgram program, int slot) {
    assertEquals(StatusCode.OK, program.argument(slot, SqlTypeDescriptor.INTEGER));
  }

  private static TransactionProgram unary(int source, int target, int operator) {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(0, source));
    assertEquals(StatusCode.OK, program.operator(operator, target));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    return program;
  }

  private static TransactionProgram select(int descriptor) {
    TransactionProgram program = new TransactionProgram();
    assertEquals(StatusCode.OK,
        program.beginStep(1, TransactionProgramAction.COMMAND));
    assertEquals(StatusCode.OK, program.beginParameter());
    assertEquals(StatusCode.OK, program.argument(0, SqlTypeDescriptor.BOOLEAN));
    assertEquals(StatusCode.OK, program.argument(1, descriptor));
    assertEquals(StatusCode.OK, program.argument(2, descriptor));
    assertEquals(StatusCode.OK,
        program.operator(TransactionScalarOperator.SELECT, descriptor));
    assertEquals(StatusCode.OK, program.endExpression());
    assertEquals(StatusCode.OK, program.endStep());
    assertEquals(StatusCode.OK, program.freeze());
    return program;
  }

  private void assertText(String expected) {
    assertEquals(expected.length(), evaluator.textLength());
    for (int index = 0; index < expected.length(); index++) {
      assertEquals(expected.charAt(index), evaluator.textCharacter(index));
    }
  }

  private static final class UnlimitedBudget implements SqlRetainedBudget {
    @Override public StatusCode reserveRetainedBytes(long bytes) { return StatusCode.OK; }
    @Override public StatusCode releaseRetainedBytes(long bytes) { return StatusCode.OK; }
  }
}
