package io.riverdb.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

/** Truthful metadata for the deliberately small pre-V1 JDBC surface. */
final class RiverDatabaseMetaData extends AbstractDatabaseMetaData {
  private static final String PRODUCT_VERSION = "0.1-pre-v1";
  private static final int MAXIMUM_COLUMNS = 8;
  private static final int MAXIMUM_IDENTIFIER_LENGTH = 64;

  private final RiverJdbcConnection connection;
  private final String url;

  RiverDatabaseMetaData(RiverJdbcConnection jdbcConnection, String jdbcUrl) {
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
  public boolean supportsSavepoints() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsNamedParameters() throws SQLException {
    connection.requireOpen();
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() throws SQLException {
    connection.requireOpen();
    return false;
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
  public int getMaxColumnsInSelect() throws SQLException {
    connection.requireOpen();
    return MAXIMUM_COLUMNS;
  }

  @Override
  public int getMaxColumnsInTable() throws SQLException {
    connection.requireOpen();
    return MAXIMUM_COLUMNS;
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
}
