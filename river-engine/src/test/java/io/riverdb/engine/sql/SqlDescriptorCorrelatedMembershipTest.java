package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class SqlDescriptorCorrelatedMembershipTest {
  private final ExactDecimal128.Scratch decimal = new ExactDecimal128.Scratch();

  @Test
  void wideMembershipRetainsBothLanesAndNullTruth() {
    SqlBooleanPredicateProgram membership = program(
        "amount IN (18.446744073709551617,NULL)");
    int descriptor = SqlTypeDescriptor.decimal(22, 18);
    assertEquals(StatusCode.OK,
        SqlDescriptorCorrelatedMembership.validate(membership, 0, descriptor));
    assertEquals(1, evaluate(
        membership, descriptor, "18.446744073709551617"));
    assertEquals(-1, evaluate(
        membership, descriptor, "0.000000000000000001"));

    SqlBooleanPredicateProgram negated = program(
        "amount NOT IN (18.446744073709551617,NULL)");
    assertEquals(StatusCode.OK,
        SqlDescriptorCorrelatedMembership.validate(negated, 0, descriptor));
    assertEquals(0, evaluate(
        negated, descriptor, "18.446744073709551617"));
    assertEquals(-1, evaluate(
        negated, descriptor, "0.000000000000000001"));
  }

  private int evaluate(
      SqlBooleanPredicateProgram program, int descriptor, String value) {
    BigInteger unscaled = new BigDecimal(value).unscaledValue();
    return SqlDescriptorCorrelatedMembership.evaluate(
        program,
        0,
        unscaled.shiftRight(Long.SIZE).longValue(),
        unscaled.longValue(),
        descriptor,
        decimal);
  }

  private static SqlBooleanPredicateProgram program(String predicate) {
    SqlCommand command = new SqlCommand();
    assertEquals(StatusCode.OK, new SqlParser().parse(
        "SELECT amount FROM values_table WHERE " + predicate, command));
    return command.wherePredicates();
  }
}
