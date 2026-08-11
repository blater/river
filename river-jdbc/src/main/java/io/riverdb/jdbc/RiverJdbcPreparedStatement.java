package io.riverdb.jdbc;

import io.riverdb.engine.api.RiverSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Bounded BIGINT prepared statement with injection-safe numeric rendering. */
final class RiverJdbcPreparedStatement extends AbstractPreparedStatement {
  static final int MAXIMUM_PARAMETERS = 512;
  static final int MAXIMUM_RENDERED_CHARACTERS = 16 * 1024;

  private static final String MINIMUM_LONG = "-9223372036854775808";

  private final String template;
  private final long[] parameters;
  private final boolean[] assigned;
  private final char[] rendered;

  RiverJdbcPreparedStatement(
      RiverJdbcConnection owner,
      RiverSession session,
      String sql) throws SQLException {
    super(owner, session);
    if (sql == null || sql.isEmpty()) {
      throw JdbcExceptions.invalid("prepared SQL must not be empty");
    }
    if (sql.length() > MAXIMUM_RENDERED_CHARACTERS) {
      throw JdbcExceptions.invalid("prepared SQL exceeds the bounded protocol payload");
    }
    int count = countParameters(sql);
    if (count > MAXIMUM_PARAMETERS) {
      throw JdbcExceptions.invalid("prepared SQL has too many parameters");
    }
    int capacity = sql.length() + count * 19;
    if (capacity > MAXIMUM_RENDERED_CHARACTERS) {
      throw JdbcExceptions.invalid("rendered SQL exceeds the bounded protocol payload");
    }
    template = sql;
    parameters = new long[count];
    assigned = new boolean[count];
    rendered = new char[capacity];
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    return super.executeQuery(render());
  }

  @Override
  public int executeUpdate() throws SQLException {
    return super.executeUpdate(render());
  }

  @Override
  public boolean execute() throws SQLException {
    return super.execute(render());
  }

  @Override
  public void addBatch() throws SQLException {
    addSqlBatch(render());
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    return executeUpdate();
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept execution SQL");
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept execution SQL");
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept execution SQL");
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    throw JdbcExceptions.invalid("prepared statements do not accept batch SQL");
  }

  @Override
  public void setBoolean(int index, boolean value) throws SQLException {
    setLong(index, value ? 1 : 0);
  }

  @Override
  public void setByte(int index, byte value) throws SQLException {
    setLong(index, value);
  }

  @Override
  public void setShort(int index, short value) throws SQLException {
    setLong(index, value);
  }

  @Override
  public void setInt(int index, int value) throws SQLException {
    setLong(index, value);
  }

  @Override
  public void setLong(int index, long value) throws SQLException {
    requireParameter(index);
    parameters[index - 1] = value;
    assigned[index - 1] = true;
  }

  @Override
  public void setObject(int index, Object value) throws SQLException {
    if (value instanceof Byte number) {
      setLong(index, number.longValue());
    } else if (value instanceof Short number) {
      setLong(index, number.longValue());
    } else if (value instanceof Integer number) {
      setLong(index, number.longValue());
    } else if (value instanceof Long number) {
      setLong(index, number.longValue());
    } else {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public void setObject(int index, Object value, int targetType) throws SQLException {
    if (targetType != Types.BIGINT) {
      throw JdbcExceptions.unsupported();
    }
    setObject(index, value);
  }

  @Override
  public void setObject(
      int index,
      Object value,
      int targetType,
      int scale) throws SQLException {
    if (scale != 0) {
      throw JdbcExceptions.unsupported();
    }
    setObject(index, value, targetType);
  }

  @Override
  public void clearParameters() throws SQLException {
    requirePreparedOpen();
    for (int index = 0; index < assigned.length; index++) {
      assigned[index] = false;
      parameters[index] = 0;
    }
  }

  private String render() throws SQLException {
    requirePreparedOpen();
    for (int index = 0; index < assigned.length; index++) {
      if (!assigned[index]) {
        throw JdbcExceptions.invalid("parameter " + (index + 1) + " is not set");
      }
    }
    int output = 0;
    int parameter = 0;
    for (int index = 0; index < template.length(); index++) {
      char value = template.charAt(index);
      if (value == '?') {
        output = writeLong(rendered, output, parameters[parameter++]);
      } else {
        rendered[output++] = value;
      }
    }
    return new String(rendered, 0, output);
  }

  private void requireParameter(int index) throws SQLException {
    requirePreparedOpen();
    if (index <= 0 || index > parameters.length) {
      throw JdbcExceptions.invalid("parameter index is out of range");
    }
  }

  private void requirePreparedOpen() throws SQLException {
    if (isClosed()) {
      throw JdbcExceptions.closed("prepared statement");
    }
  }

  private static int countParameters(String sql) {
    int count = 0;
    for (int index = 0; index < sql.length(); index++) {
      if (sql.charAt(index) == '?') {
        count++;
      }
    }
    return count;
  }

  private static int writeLong(char[] target, int offset, long value) {
    if (value == Long.MIN_VALUE) {
      MINIMUM_LONG.getChars(0, MINIMUM_LONG.length(), target, offset);
      return offset + MINIMUM_LONG.length();
    }
    int start = offset;
    long positive = value;
    if (value < 0) {
      target[offset++] = '-';
      start = offset;
      positive = -value;
    }
    int digits = positive == 0 ? 1 : 0;
    for (long remaining = positive; remaining > 0; remaining /= 10) {
      digits++;
    }
    int end = start + digits;
    int position = end;
    do {
      target[--position] = (char) ('0' + positive % 10);
      positive /= 10;
    } while (positive > 0);
    return end;
  }
}
