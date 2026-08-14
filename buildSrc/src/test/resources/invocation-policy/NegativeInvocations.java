package fixture.invocation;

final class NegativeInvocations {
  RelationalSession allowedOwnerTypeReference;
  TableDefinition allowedTargetTypeReference;

  int forbiddenResolve(
      RelationalSession session,
      CharSequence name,
      TableDefinition definition) {
    session.resolveTable(name, definition);
    return session.resolveTable(name, definition);
  }

  int allowedNearbyCalls(
      RelationalSession session,
      OtherSession other,
      CharSequence name,
      TableDefinition definition) {
    return session.resolveTable(name.toString(), definition)
        + session.resolveOther(name, definition)
        + other.resolveTable(name, definition)
        + definition.findColumn(name.toString());
  }

  static final class Nested {
    int forbiddenFindColumn(TableDefinition definition, CharSequence name) {
      definition.findColumn(name);
      return definition.findColumn(name);
    }
  }
}

final class QueryExecution {
  int forbiddenFindColumn(TableDefinition definition, CharSequence name) {
    definition.findColumn(name);
    return definition.findColumn(name);
  }

  int allowedNearbyCalls(
      TableDefinition definition, OtherDefinition other, CharSequence name) {
    return definition.findColumn(name.toString())
        + definition.findOther(name)
        + other.findColumn(name);
  }

  static final class Nested {
    int forbiddenFindColumn(TableDefinition definition, CharSequence name) {
      definition.findColumn(name);
      return definition.findColumn(name);
    }
  }
}

final class RelationalSession {
  int resolveTable(CharSequence name, TableDefinition definition) {
    return 0;
  }

  int resolveTable(String name, TableDefinition definition) {
    return 0;
  }

  int resolveOther(CharSequence name, TableDefinition definition) {
    return 0;
  }
}

final class OtherSession {
  int resolveTable(CharSequence name, TableDefinition definition) {
    return 0;
  }
}

final class TableDefinition {
  int findColumn(CharSequence name) {
    return 0;
  }

  int findColumn(String name) {
    return 0;
  }

  int findOther(CharSequence name) {
    return 0;
  }
}

final class OtherDefinition {
  int findColumn(CharSequence name) {
    return 0;
  }
}
