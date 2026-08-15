package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Rehomes one derived predicate's bounded literal payload. */
final class SqlDerivedPredicateValues {
  private final long[] membershipValues =
      new long[SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES];

  StatusCode copy(SqlCommand source, int predicate, SqlCommand destination) {
    if (source.isNullPredicate(predicate)) {
      destination.appendNullPredicate(source.isNullPredicateNegated(predicate));
      return StatusCode.OK;
    }
    return source.isLiteralMembership(predicate)
        ? copyMembership(source, predicate, destination)
        : copyScalar(source, predicate, destination);
  }

  private StatusCode copyMembership(
      SqlCommand source, int predicate, SqlCommand destination) {
    int count = source.literalMembershipCount(predicate);
    for (int index = 0; index < count; index++) {
      long value = copyLiteral(
          source, predicate, source.literalMembershipValue(predicate, index), destination);
      if (varchar(source, predicate)
          && value == SqlCommand.INVALID_TEXT_HANDLE) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      membershipValues[index] = value;
    }
    return destination.appendLiteralMembership(
        membershipValues,
        count,
        source.literalMembershipHasNull(predicate),
        source.comparison(predicate) == SqlComparison.NOT_IN,
        source.predicateTypeDescriptor(predicate));
  }

  private static StatusCode copyScalar(
      SqlCommand source, int predicate, SqlCommand destination) {
    long value = copyLiteral(
        source, predicate, source.predicateValue(predicate), destination);
    if (varchar(source, predicate)
        && value == SqlCommand.INVALID_TEXT_HANDLE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (!source.isRangePredicate(predicate)) {
      destination.appendComparison(
          value,
          source.comparison(predicate),
          source.predicateTypeDescriptor(predicate));
      return StatusCode.OK;
    }
    long lower = copyLiteral(
        source, predicate, source.predicateLowerInclusive(predicate), destination);
    long upper = copyLiteral(
        source, predicate, source.predicateUpperExclusive(predicate), destination);
    if (varchar(source, predicate)
        && (lower == SqlCommand.INVALID_TEXT_HANDLE
            || upper == SqlCommand.INVALID_TEXT_HANDLE)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    destination.appendPredicate(
        value,
        lower,
        upper,
        false,
        source.predicateTypeDescriptor(predicate));
    return StatusCode.OK;
  }

  private static long copyLiteral(
      SqlCommand source, int predicate, long value, SqlCommand destination) {
    return varchar(source, predicate)
        ? destination.copyTextFrom(source, value) : value;
  }

  private static boolean varchar(SqlCommand source, int predicate) {
    return SqlTypeDescriptor.typeId(source.predicateTypeDescriptor(predicate))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
