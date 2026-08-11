package io.riverdb.jdbc;

import io.riverdb.base.text.PackedText;
import io.riverdb.engine.api.RiverSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Bounded BIGINT/VARCHAR prepared statement with injection-safe rendering. */
final class RiverJdbcPreparedStatement extends AbstractPreparedStatement {
  static final int MAXIMUM_PARAMETERS = 512;
  static final int MAXIMUM_RENDERED_CHARACTERS = 16 * 1024;

  private static final String MINIMUM_LONG = "-9223372036854775808";
  private static final byte PARAMETER_UNSET = 0;
  private static final byte PARAMETER_LONG = 1;
  private static final byte PARAMETER_VARCHAR = 2;

  private final String template;
  private final long[] parameters;
  private final String[] textParameters;
  private final byte[] parameterTypes;
  private final char[] rendered;
  private final boolean returnGeneratedKeys;

  RiverJdbcPreparedStatement(
      RiverJdbcConnection owner,
      RiverSession session,
      String sql) throws SQLException {
    this(owner, session, sql, false);
  }

  RiverJdbcPreparedStatement(
      RiverJdbcConnection owner,
      RiverSession session,
      String sql,
      boolean generatedKeys) throws SQLException {
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
    textParameters = new String[count];
    parameterTypes = new byte[count];
    rendered = new char[capacity];
    returnGeneratedKeys = generatedKeys;
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    return super.executeQuery(render());
  }

  @Override
  public int executeUpdate() throws SQLException {
    return returnGeneratedKeys
        ? super.executeUpdate(render(), RETURN_GENERATED_KEYS)
        : super.executeUpdate(render());
  }

  @Override
  public boolean execute() throws SQLException {
    return returnGeneratedKeys
        ? super.execute(render(), RETURN_GENERATED_KEYS)
        : super.execute(render());
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
    textParameters[index - 1] = null;
    parameterTypes[index - 1] = PARAMETER_LONG;
  }

  @Override
  public void setString(int index, String value) throws SQLException {
    requireParameter(index);
    if (!PackedText.isValid(value)) {
      throw JdbcExceptions.invalid("VARCHAR parameter exceeds the supported domain");
    }
    parameters[index - 1] = 0;
    textParameters[index - 1] = value;
    parameterTypes[index - 1] = PARAMETER_VARCHAR;
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
    } else if (value instanceof String text) {
      setString(index, text);
    } else {
      throw JdbcExceptions.unsupported();
    }
  }

  @Override
  public void setObject(int index, Object value, int targetType) throws SQLException {
    if (targetType == Types.VARCHAR && value instanceof String text) {
      setString(index, text);
      return;
    }
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
    for (int index = 0; index < parameterTypes.length; index++) {
      parameterTypes[index] = PARAMETER_UNSET;
      parameters[index] = 0;
      textParameters[index] = null;
    }
  }

  private String render() throws SQLException {
    requirePreparedOpen();
    for (int index = 0; index < parameterTypes.length; index++) {
      if (parameterTypes[index] == PARAMETER_UNSET) {
        throw JdbcExceptions.invalid("parameter " + (index + 1) + " is not set");
      }
    }
    int output = 0;
    int parameter = 0;
    for (int index = 0; index < template.length(); index++) {
      char value = template.charAt(index);
      if (value == '?') {
        output = parameterTypes[parameter] == PARAMETER_VARCHAR
            ? writeText(rendered, output, textParameters[parameter++])
            : writeLong(rendered, output, parameters[parameter++]);
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

  private static int writeText(char[] target, int offset, String value) {
    target[offset++] = '\'';
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      target[offset++] = character;
      if (character == '\'') {
        target[offset++] = '\'';
      }
    }
    target[offset++] = '\'';
    return offset;
  }
}
