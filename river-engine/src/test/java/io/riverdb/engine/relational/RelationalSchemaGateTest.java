package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class RelationalSchemaGateTest {
  @Test
  void rejectsNullWrongAndInactivePublishersWithoutChangingVersion() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    RelationalSession owner = session(gate);
    RelationalSession other = session(gate);
    long initialVersion = gate.version();

    assertEquals(StatusCode.NOT_OWNER, gate.publishOwnedSchema(null));
    assertEquals(StatusCode.NOT_OWNER, gate.publishOwnedSchema(owner));
    gate.completeSchemaChange(null, true);
    gate.completeSchemaChange(owner, true);
    assertEquals(initialVersion, gate.version());

    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.OK, gate.beginSchemaChange(owner));
    assertEquals(StatusCode.NOT_OWNER, gate.publishOwnedSchema(other));
    gate.completeSchemaChange(null, true);
    gate.completeSchemaChange(other, true);
    assertEquals(initialVersion, gate.version());
    gate.completeSchemaChange(owner, false);
    gate.leaveTransaction();
  }

  @Test
  void committedCompletionIncrementsOnceAndAbortDoesNotIncrement() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    RelationalSession owner = session(gate);
    long initialVersion = gate.version();

    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.OK, gate.beginSchemaChange(owner));
    gate.completeSchemaChange(owner, true);
    assertEquals(initialVersion + 1, gate.version());
    gate.leaveTransaction();

    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.OK, gate.beginSchemaChange(owner));
    gate.completeSchemaChange(owner, false);
    assertEquals(initialVersion + 1, gate.version());
    gate.leaveTransaction();
  }

  @Test
  void ownedPublicationIncrementsOnceAndRetainsOwnershipUntilCompletion() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    RelationalSession owner = session(gate);
    RelationalSession other = session(gate);
    long initialVersion = gate.version();

    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.OK, gate.beginSchemaChange(owner));
    assertEquals(StatusCode.OK, gate.publishOwnedSchema(owner));
    assertEquals(initialVersion + 1, gate.version());
    assertEquals(StatusCode.NOT_OWNER, gate.publishOwnedSchema(other));
    assertEquals(initialVersion + 1, gate.version());
    assertEquals(StatusCode.OK, gate.publishOwnedSchema(owner));
    assertEquals(initialVersion + 2, gate.version());
    gate.completeSchemaChange(owner, false);
    assertEquals(initialVersion + 2, gate.version());
    assertEquals(StatusCode.NOT_OWNER, gate.publishOwnedSchema(owner));
    assertEquals(initialVersion + 2, gate.version());
    gate.leaveTransaction();
  }

  @Test
  void activeSecondTransactionAndSequencePermitBlockSchemaAdmission() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    RelationalSession owner = session(gate);
    RelationalSession other = session(gate);

    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.OK, gate.enterTransaction(other));
    assertEquals(StatusCode.RETRY, gate.beginSchemaChange(owner));
    gate.leaveTransaction();
    gate.leaveTransaction();

    assertEquals(StatusCode.OK, gate.enterSequenceOperation());
    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.RETRY, gate.beginSchemaChange(owner));
    gate.leaveTransaction();
    gate.leaveSequenceOperation();
  }

  @Test
  void createdDefinitionSurvivesCommitButNotAbortOrLaterAdmission() {
    RelationalSchemaGate gate = new RelationalSchemaGate();
    RelationalSession owner = session(gate);
    TableDefinition committed = definition(gate, 1);

    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.OK, gate.beginSchemaChange(owner));
    assertEquals(StatusCode.OK, gate.bindOwnedDefinition(owner, committed));
    assertEquals(true, committed.isOwnedBy(gate));
    gate.completeSchemaChange(owner, true);
    assertEquals(true, committed.isOwnedBy(gate));
    gate.leaveTransaction();

    TableDefinition aborted = definition(gate, 2);
    assertEquals(StatusCode.OK, gate.enterTransaction(owner));
    assertEquals(StatusCode.OK, gate.beginSchemaChange(owner));
    assertEquals(StatusCode.OK, gate.bindOwnedDefinition(owner, aborted));
    assertEquals(true, aborted.isOwnedBy(gate));
    gate.completeSchemaChange(owner, false);
    assertEquals(false, aborted.isOwnedBy(gate));

    TableDefinition replacement = definition(gate, 3);
    assertEquals(StatusCode.OK, gate.beginSchemaChange(owner));
    assertEquals(StatusCode.OK, gate.bindOwnedDefinition(owner, replacement));
    assertEquals(false, aborted.isOwnedBy(gate));
    gate.completeSchemaChange(owner, true);
    assertEquals(false, aborted.isOwnedBy(gate));
    assertEquals(true, replacement.isOwnedBy(gate));
    gate.leaveTransaction();
  }

  private static RelationalSession session(RelationalSchemaGate gate) {
    return new RelationalSession(null, gate, null);
  }

  private static TableDefinition definition(RelationalSchemaGate gate, int tableId) {
    TableDefinition definition = new TableDefinition();
    definition.set(gate, tableId, 0, TableDefinition.INDEX_NONE);
    return definition;
  }
}
