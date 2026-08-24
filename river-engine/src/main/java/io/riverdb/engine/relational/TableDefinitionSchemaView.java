package io.riverdb.engine.relational;

/** Schema ownership and publication checks for a table definition. */
final class TableDefinitionSchemaView {
  private TableDefinitionSchemaView() { }

  static boolean isOwnedBy(TableDefinition table, RelationalSchemaGate schemaGate) {
    return schemaGate != null && schemaGate.owns(table);
  }

  static void bind(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      long requiredSchemaVersion,
      long requiredSchemaAdmission) {
    table.owner = schemaGate;
    table.schemaVersion = requiredSchemaVersion;
    table.schemaAdmission = requiredSchemaAdmission;
  }

  static boolean matches(
      TableDefinition table,
      RelationalSchemaGate schemaGate,
      long publishedSchemaVersion,
      long publishedSchemaAdmission,
      long activeSchemaAdmission) {
    if (!table.available || table.owner != schemaGate) {
      return false;
    }
    if (table.schemaAdmission == 0) {
      return table.schemaVersion == publishedSchemaVersion;
    }
    if (table.schemaVersion == publishedSchemaVersion
        && table.schemaAdmission == publishedSchemaAdmission) {
      return true;
    }
    return activeSchemaAdmission != 0
        && table.schemaAdmission == activeSchemaAdmission
        && table.schemaVersion == publishedSchemaVersion + 1;
  }
}
