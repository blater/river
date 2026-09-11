package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import java.util.Arrays;

/** Owns one statement's retained SQL and parameter batch storage. */
final class RiverJdbcBatchState {
  private String[] sql = new String[0];
  private ParameterSet[] parameters;
  private int count;

  void addSql(String statement, ParameterSet parameterSet) throws java.sql.SQLException {
    ensureCapacity();
    sql[count] = statement;
    if (parameterSet != null) {
      if (parameters == null) {
        parameters = new ParameterSet[sql.length];
      }
      parameters[count] = parameterSet;
    }
    count++;
  }

  void addPrepared(ParameterSet parameterSet) throws java.sql.SQLException {
    ensureCapacity();
    if (parameters == null) {
      parameters = new ParameterSet[sql.length];
    }
    parameters[count++] = parameterSet;
  }

  int count() {
    return count;
  }

  void beginExecution() {
    count = 0;
  }

  String takeSql(int index) {
    String statement = sql[index];
    sql[index] = null;
    return statement;
  }

  ParameterSet takeParameters(int index) {
    if (parameters == null) {
      return null;
    }
    ParameterSet parameterSet = parameters[index];
    parameters[index] = null;
    return parameterSet;
  }

  void release(int index) {
    sql[index] = null;
    if (parameters == null) {
      return;
    }
    ParameterSet parameterSet = parameters[index];
    parameters[index] = null;
    if (parameterSet != null) {
      parameterSet.reset();
    }
  }

  void clear() {
    for (int index = 0; index < count; index++) {
      release(index);
    }
    count = 0;
  }

  private void ensureCapacity() throws java.sql.SQLException {
    if (count < sql.length) {
      return;
    }
    if (count == Integer.MAX_VALUE) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "add batch entry");
    }
    int required = count + 1;
    int grown = sql.length == 0
        ? 16
        : sql.length >= Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : sql.length << 1;
    int capacity = Math.max(required, grown);
    try {
      String[] grownSql = Arrays.copyOf(sql, capacity);
      ParameterSet[] grownParameters = parameters == null
          ? null : Arrays.copyOf(parameters, capacity);
      sql = grownSql;
      parameters = grownParameters;
    } catch (OutOfMemoryError exhausted) {
      throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "add batch entry");
    }
  }
}
