package io.riverdb.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

/** Truthful metadata for the deliberately small pre-V1 JDBC surface. */
final class RiverDatabaseMetaData extends RiverShapeDatabaseMetaData {
  private static final String PRODUCT_VERSION = "0.1-pre-v1";
  private static final int MAXIMUM_IDENTIFIER_LENGTH = 64;
  private static final int MAXIMUM_TABLE_PATTERN_LENGTH = 128;
  private static final int MAXIMUM_TABLE_TYPES = 16;

  private final RiverJdbcConnection connection;
  private final String url;

  RiverDatabaseMetaData(RiverJdbcConnection jdbcConnection, String jdbcUrl) {
    super(jdbcConnection);
    connection = jdbcConnection;
    url = jdbcUrl;
  }

  @Override
  public String getURL() throws SQLException {
    connection.requireOpen();
    return url;
  }

  @Override
  public String getUserName() throws SQLException {
    connection.requireOpen();
    return "";
  }

  @Override
  public String getDatabaseProductName() throws SQLException {
    connection.requireOpen();
    return "River";
  }

  @Override
  public String getDatabaseProductVersion() throws SQLException {
    connection.requireOpen();
    return PRODUCT_VERSION;
  }

  @Override
  public String getDriverName() throws SQLException {
    connection.requireOpen();
    return "River JDBC";
  }

  @Override
  public String getDriverVersion() throws SQLException {
    connection.requireOpen();
    return PRODUCT_VERSION;
  }

  @Override
  public int getDriverMajorVersion() {
    return 0;
  }

  @Override
  public int getDriverMinorVersion() {
    return 1;
  }

  @Override
  public int getDatabaseMajorVersion() throws SQLException {
    connection.requireOpen();
    return 0;
  }

  @Override
  public int getDatabaseMinorVersion() throws SQLException {
    connection.requireOpen();
    return 1;
  }

  @Override
  public int getJDBCMajorVersion() throws SQLException {
    connection.requireOpen();
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() throws SQLException {
    connection.requireOpen();
    return 3;
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean usesLocalFiles() throws SQLException {
    connection.requireOpen();
    return true;
  }

  @Override
  public boolean usesLocalFilePerTable() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() throws SQLException {
    connection.requireOpen();
    return true;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
    connection.requireOpen();
    return false;

  }
  @Override
  public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public String getIdentifierQuoteString() throws SQLException {
    connection.requireOpen();
    return " ";
  }

  @Override
  public String getCatalogSeparator() throws SQLException {
    connection.requireOpen();
    return "";
  }

  @Override
  public String getCatalogTerm() throws SQLException {
    connection.requireOpen();
    return "";
  }

  @Override
  public String getSchemaTerm() throws SQLException {
    connection.requireOpen();
    return "";
  }

  @Override
  public String getProcedureTerm() throws SQLException {
    connection.requireOpen();
    return "";
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsSchemasInDataManipulation() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsTransactions() throws SQLException {
    connection.requireOpen();
    return true;
  }

  @Override
  public int getDefaultTransactionIsolation() throws SQLException {
    connection.requireOpen();
    return Connection.TRANSACTION_REPEATABLE_READ;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
    connection.requireOpen();
    return level == Connection.TRANSACTION_READ_COMMITTED
        || level == Connection.TRANSACTION_REPEATABLE_READ
        || level == Connection.TRANSACTION_SERIALIZABLE;
  }

  @Override
  public boolean supportsBatchUpdates() throws SQLException {
    connection.requireOpen();
    return true;
  }

  @Override
  public ResultSet getTables(
      String catalog,
      String schemaPattern,
      String tableNamePattern,
      String[] types) throws SQLException {
    connection.requireOpen();
    if (tableNamePattern != null
        && tableNamePattern.length() > MAXIMUM_TABLE_PATTERN_LENGTH) {
      throw JdbcExceptions.invalid("table name pattern is too long");
    }
    if (types != null && types.length > MAXIMUM_TABLE_TYPES) {
      throw JdbcExceptions.invalid("too many table type filters");
    }
    boolean includeTables = types == null;
    boolean includeViews = types == null;
    if (types != null) {
      for (String type : types) {
        includeTables |= "TABLE".equalsIgnoreCase(type);
        includeViews |= "VIEW".equalsIgnoreCase(type);
      }
    }
    boolean namespaceMatches = absentNamespace(catalog) && matchesAbsentSchema(schemaPattern);
    return connection.openTables(
        tableNamePattern == null ? "%" : tableNamePattern,
        includeTables,
        includeViews,
        namespaceMatches);
  }

  @Override
  public ResultSet getTableTypes() throws SQLException {
    connection.requireOpen();
    return connection.openTableTypes();
  }

  @Override
  public ResultSet getColumns(
      String catalog,
      String schemaPattern,
      String tableNamePattern,
      String columnNamePattern) throws SQLException {
    connection.requireOpen();
    if (tableNamePattern != null
        && tableNamePattern.length() > MAXIMUM_TABLE_PATTERN_LENGTH) {
      throw JdbcExceptions.invalid("table name pattern is too long");
    }
    if (columnNamePattern != null
        && columnNamePattern.length() > MAXIMUM_TABLE_PATTERN_LENGTH) {
      throw JdbcExceptions.invalid("column name pattern is too long");
    }
    boolean namespaceMatches = absentNamespace(catalog) && matchesAbsentSchema(schemaPattern);
    return connection.openColumns(
        tableNamePattern == null ? "%" : tableNamePattern,
        columnNamePattern == null ? "%" : columnNamePattern,
        namespaceMatches);
  }

  @Override
  public ResultSet getPrimaryKeys(
      String catalog,
      String schema,
      String table) throws SQLException {
    connection.requireOpen();
    if (table == null || table.isEmpty() || table.length() > MAXIMUM_IDENTIFIER_LENGTH) {
      throw JdbcExceptions.invalid("table name is outside the bounded identifier domain");
    }
    return connection.openPrimaryKeys(
        table,
        absentNamespace(catalog) && absentNamespace(schema));
  }

  @Override
  public ResultSet getIndexInfo(
      String catalog,
      String schema,
      String table,
      boolean unique,
      boolean approximate) throws SQLException {
    connection.requireOpen();
    if (table == null || table.isEmpty() || table.length() > MAXIMUM_IDENTIFIER_LENGTH) {
      throw JdbcExceptions.invalid("table name is outside the bounded identifier domain");
    }
    return connection.openIndexInfo(
        table,
        unique,
        absentNamespace(catalog) && absentNamespace(schema));
  }

  @Override
  public String getSearchStringEscape() throws SQLException {
    connection.requireOpen();
    return "\\";
  }

  @Override
  public boolean supportsSavepoints() throws SQLException {
    connection.requireOpen();
    return true;
  }

  @Override
  public boolean supportsNamedParameters() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() throws SQLException {
    connection.requireOpen();
    return true;
  }

  @Override
  public boolean supportsResultSetType(int type) throws SQLException {
    connection.requireOpen();
    return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
    connection.requireOpen();
    return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) throws SQLException {
    connection.requireOpen();
    return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    connection.requireOpen();
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getMaxColumnNameLength() throws SQLException {
    connection.requireOpen();
    return MAXIMUM_IDENTIFIER_LENGTH;
  }

  @Override
  public int getMaxTableNameLength() throws SQLException {
    connection.requireOpen();
    return MAXIMUM_IDENTIFIER_LENGTH;
  }

  @Override
  public boolean supportsStatementPooling() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() throws SQLException {
    connection.requireOpen();
    return RowIdLifetime.ROWID_UNSUPPORTED;
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    connection.requireOpen();
    return true;
  }

  @Override
  public int getSQLStateType() throws SQLException {
    connection.requireOpen();
    return DatabaseMetaData.sqlStateSQL;
  }

  @Override
  public boolean generatedKeyAlwaysReturned() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsRefCursors() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsSharding() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public Connection getConnection() throws SQLException {
    connection.requireOpen();
    return connection;
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    connection.requireOpen();
    if (type != null && type.isInstance(this)) {
      return type.cast(this);
    }
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(Class<?> type) throws SQLException {
    connection.requireOpen();
    return type != null && type.isInstance(this);
  }

  private static boolean absentNamespace(String value) {
    return value == null || value.isEmpty();
  }

  private static boolean matchesAbsentSchema(String pattern) {
    if (pattern == null) {
      return true;
    }
    for (int index = 0; index < pattern.length(); index++) {
      if (pattern.charAt(index) != '%') {
        return false;
      }
    }
    return true;
  }
}
