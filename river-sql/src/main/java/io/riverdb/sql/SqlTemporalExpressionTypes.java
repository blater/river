package io.riverdb.sql;

/** Temporal postfix field and result-descriptor rules. */
final class SqlTemporalExpressionTypes {
  private SqlTemporalExpressionTypes() {
  }

  static int extractField(SqlParserInput input, CharSequence sql) {
    return SqlTemporalFieldRules.extractField(input, sql);
  }

  static int extractDescriptor(int source, int field) {
    return SqlTemporalFieldRules.extractDescriptor(source, field);
  }

  static int additiveDescriptor(int operator, int left, int right) {
    return SqlTemporalFieldRules.additiveDescriptor(operator, left, right);
  }

  static boolean consumeComputedStart(SqlParserInput input, CharSequence sql) {
    return SqlTemporalComputedStart.consume(input, sql);
  }

  static boolean isComputedStart(CharSequence identifier) {
    return SqlTemporalComputedStart.isComputed(identifier);
  }
}
