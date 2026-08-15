package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

/** Executes one bounded script through an admitted River session. */
final class RiverSqlExecution {
  private final QueryOpenResult opened = new QueryOpenResult();
  private final CommandResult command = new CommandResult();
  private final RowResult row = new RowResult();
  private final CommandResult closed = new CommandResult();
  private final RiverSqlRowFormatter formatter = new RiverSqlRowFormatter();

  int run(
      RiverSession session,
      InputStream input,
      PrintStream output,
      PrintStream errors) throws IOException {
    RiverSqlScriptReader reader = new RiverSqlScriptReader(input);
    StatusCode status;
    while ((status = reader.next()).isOk() && reader.isAvailable()) {
      int exit = execute(
          session, reader.statement(), reader.statementLength(), output, errors);
      if (exit != 0) return exit;
    }
    if (!status.isOk()) {
      RiverSqlMain.report(errors, status);
      return 1;
    }
    return 0;
  }

  private int execute(
      RiverSession session,
      char[] characters,
      int length,
      PrintStream output,
      PrintStream errors) {
    String sql = new String(characters, 0, length);
    opened.reset();
    StatusCode status = session.beginQuery(sql, opened);
    if (status.isOk()) return stream(opened.query(), output, errors);
    if (status != StatusCode.INVALID_EXTERNAL_INPUT) {
      RiverSqlMain.report(errors, status);
      return 1;
    }
    command.reset();
    status = session.execute(sql, command);
    if (!status.isOk()) {
      RiverSqlMain.report(errors, status);
      return 1;
    }
    output.print("UPDATE\t");
    output.print(command.affectedRows());
    output.print('\t');
    output.println(command.commitSequence());
    return 0;
  }

  private int stream(
      RiverQuery query, PrintStream output, PrintStream errors) {
    for (int index = 0; index < query.columnCount(); index++) {
      if (index > 0) output.print('\t');
      output.print(query.columnName(index));
    }
    output.println();
    long rows = 0;
    StatusCode status;
    while ((status = query.next(row)).isOk() && row.isAvailable()) {
      for (int index = 0; index < row.columnCount(); index++) {
        if (index > 0) output.print('\t');
        status = formatter.print(row, index, output);
        if (!status.isOk()) break;
      }
      if (!status.isOk()) break;
      output.println();
      rows++;
    }
    closed.reset();
    StatusCode close = query.close(closed);
    if (!status.isOk() || !close.isOk()) {
      RiverSqlMain.report(errors, status.isOk() ? close : status);
      return 1;
    }
    output.print("ROWS\t");
    output.println(rows);
    return 0;
  }
}
