package io.riverdb.sql;

/** Maps one aggregate kind to its scalar or grouped command family. */
final class SqlAggregateCommandType {
  private SqlAggregateCommandType() { }

  static SqlCommandType route(int kind, boolean grouped) {
    return grouped ? grouped(kind) : scalar(kind);
  }

  static SqlCommandType grouped(SqlCommandType type) {
    return switch (type) {
      case COUNT -> SqlCommandType.GROUP_COUNT;
      case COUNT_VALUE -> SqlCommandType.GROUP_COUNT_VALUE;
      case COUNT_DISTINCT -> SqlCommandType.GROUP_COUNT_DISTINCT;
      case SUM -> SqlCommandType.GROUP_SUM;
      case AVG -> SqlCommandType.GROUP_AVG;
      case MIN -> SqlCommandType.GROUP_MIN;
      case MAX -> SqlCommandType.GROUP_MAX;
      default -> type;
    };
  }

  private static SqlCommandType scalar(int kind) {
    return switch (kind) {
      case SqlAggregateKind.COUNT -> SqlCommandType.COUNT;
      case SqlAggregateKind.COUNT_VALUE -> SqlCommandType.COUNT_VALUE;
      case SqlAggregateKind.COUNT_DISTINCT -> SqlCommandType.COUNT_DISTINCT;
      case SqlAggregateKind.SUM -> SqlCommandType.SUM;
      case SqlAggregateKind.AVG -> SqlCommandType.AVG;
      case SqlAggregateKind.MIN -> SqlCommandType.MIN;
      case SqlAggregateKind.MAX -> SqlCommandType.MAX;
      default -> null;
    };
  }

  private static SqlCommandType grouped(int kind) {
    return switch (kind) {
      case SqlAggregateKind.COUNT -> SqlCommandType.GROUP_COUNT;
      case SqlAggregateKind.COUNT_VALUE -> SqlCommandType.GROUP_COUNT_VALUE;
      case SqlAggregateKind.COUNT_DISTINCT -> SqlCommandType.GROUP_COUNT_DISTINCT;
      case SqlAggregateKind.SUM -> SqlCommandType.GROUP_SUM;
      case SqlAggregateKind.AVG -> SqlCommandType.GROUP_AVG;
      case SqlAggregateKind.MIN -> SqlCommandType.GROUP_MIN;
      case SqlAggregateKind.MAX -> SqlCommandType.GROUP_MAX;
      default -> null;
    };
  }
}
