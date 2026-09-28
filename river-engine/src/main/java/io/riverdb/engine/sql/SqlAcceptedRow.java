package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.RelationalSession;
import java.nio.ByteBuffer;

/** A synchronous, borrowed SQL row whose new values passed the encoder's admission checks. */
public final class SqlAcceptedRow {
  private RelationalSession owner;
  private TableDefinition table;
  private ByteBuffer bytes;

  SqlAcceptedRow() { }

  void accept(RelationalSession session, TableDefinition definition, ByteBuffer row) {
    owner = session;
    table = definition;
    bytes = row;
  }

  public boolean belongsTo(RelationalSession session, TableDefinition definition) {
    return owner == session && table == definition && bytes != null;
  }
  public ByteBuffer bytes() { return bytes; }
}
