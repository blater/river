package io.riverdb.jdbc;

@SuppressWarnings("deprecation")
abstract class AbstractDatabaseMetaData implements java.sql.DatabaseMetaData {
  @Override
  public boolean allProceduresAreCallable() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean allTablesAreSelectable() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean deletesAreDetected(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean generatedKeyAlwaysReturned() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getAttributes(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getBestRowIdentifier(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, int argument3, boolean argument4) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getCatalogSeparator() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getCatalogTerm() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getCatalogs() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getClientInfoProperties() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getColumnPrivileges(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getColumns(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.Connection getConnection() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getCrossReference(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String argument3, java.lang.String argument4, java.lang.String argument5) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getDatabaseMajorVersion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getDatabaseMinorVersion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getDatabaseProductName() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getDatabaseProductVersion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getDefaultTransactionIsolation() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public abstract int getDriverMajorVersion();

  @Override
  public abstract int getDriverMinorVersion();

  @Override
  public java.lang.String getDriverName() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getDriverVersion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getExportedKeys(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getExtraNameCharacters() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getFunctionColumns(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getFunctions(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getIdentifierQuoteString() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getImportedKeys(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getIndexInfo(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, boolean argument3, boolean argument4) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getJDBCMajorVersion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getJDBCMinorVersion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxBinaryLiteralLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxCatalogNameLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxCharLiteralLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxColumnNameLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxColumnsInGroupBy() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxColumnsInIndex() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxColumnsInOrderBy() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxColumnsInSelect() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxColumnsInTable() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxConnections() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxCursorNameLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxIndexLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxProcedureNameLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxRowSize() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxSchemaNameLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxStatementLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxStatements() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxTableNameLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxTablesInSelect() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getMaxUserNameLength() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getNumericFunctions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getPrimaryKeys(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getProcedureColumns(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getProcedureTerm() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getProcedures(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getPseudoColumns(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getResultSetHoldability() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.RowIdLifetime getRowIdLifetime() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getSQLKeywords() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public int getSQLStateType() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getSchemaTerm() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getSchemas() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getSchemas(java.lang.String argument0, java.lang.String argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getSearchStringEscape() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getStringFunctions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getSuperTables(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getSuperTypes(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getSystemFunctions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getTablePrivileges(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getTableTypes() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getTables(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, java.lang.String[] argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getTimeDateFunctions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getTypeInfo() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getUDTs(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2, int[] argument3) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getURL() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.lang.String getUserName() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public java.sql.ResultSet getVersionColumns(java.lang.String argument0, java.lang.String argument1, java.lang.String argument2) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean insertsAreDetected(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isCatalogAtStart() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isReadOnly() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean isWrapperFor(java.lang.Class<?> argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean locatorsUpdateCopy() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean nullPlusNonNullIsNull() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean nullsAreSortedAtEnd() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean nullsAreSortedAtStart() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean nullsAreSortedHigh() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean nullsAreSortedLow() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean othersDeletesAreVisible(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean othersInsertsAreVisible(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean othersUpdatesAreVisible(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean ownDeletesAreVisible(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean ownInsertsAreVisible(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean ownUpdatesAreVisible(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean storesLowerCaseIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean storesMixedCaseIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean storesUpperCaseIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsANSI92FullSQL() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsBatchUpdates() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsColumnAliasing() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsConvert() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsConvert(int argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsCoreSQLGrammar() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsCorrelatedSubqueries() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsExpressionsInOrderBy() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsExtendedSQLGrammar() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsFullOuterJoins() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsGetGeneratedKeys() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsGroupBy() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsGroupByBeyondSelect() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsGroupByUnrelated() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsLikeEscapeClause() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsLimitedOuterJoins() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsMinimumSQLGrammar() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsMultipleOpenResults() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsMultipleResultSets() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsMultipleTransactions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsNamedParameters() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsNonNullableColumns() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsOrderByUnrelated() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsOuterJoins() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsPositionedDelete() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsPositionedUpdate() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsResultSetConcurrency(int argument0, int argument1) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsResultSetHoldability(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsResultSetType(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSavepoints() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSchemasInDataManipulation() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSelectForUpdate() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsStatementPooling() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsStoredProcedures() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSubqueriesInComparisons() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSubqueriesInExists() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSubqueriesInIns() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsTableCorrelationNames() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsTransactions() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsUnion() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean supportsUnionAll() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public <T> T unwrap(java.lang.Class<T> argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean updatesAreDetected(int argument0) throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean usesLocalFilePerTable() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

  @Override
  public boolean usesLocalFiles() throws java.sql.SQLException {
    throw JdbcExceptions.unsupported();
  }

}
