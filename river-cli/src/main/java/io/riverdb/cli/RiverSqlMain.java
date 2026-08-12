package io.riverdb.cli;

import io.riverdb.base.error.StatusCode;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.client.RiverClientOpenResult;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Bounded script-oriented SQL client for a loopback River server. */
public final class RiverSqlMain {
  static final int MAXIMUM_STATEMENT_CHARACTERS = 64 * 1024;

  private RiverSqlMain() {
  }

  public static void main(String[] arguments) {
    int port = arguments.length == 1 ? parsePort(arguments[0]) : -1;
    int exit = port > 0
        ? run(port, System.in, System.out, System.err) : usage(System.err);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  public static int run(
      int port,
      InputStream input,
      PrintStream output,
      PrintStream errors) {
    if (port <= 0 || port > 65_535 || input == null || output == null || errors == null) {
      return 2;
    }
    RiverClientOpenResult connected = new RiverClientOpenResult();
    StatusCode status = RiverClientConnection.connectLoopback(port, connected);
    if (!status.isOk()) {
      error(errors, status);
      return 1;
    }
    RiverClientConnection client = connected.connection();
    SessionOpenResult opened = new SessionOpenResult();
    status = client.createSession(opened);
    if (!status.isOk()) {
      error(errors, status);
      client.close();
      return 1;
    }
    RiverSession session = opened.session();
    int exit;
    try {
      exit = runScript(session, input, output, errors);
    } catch (IOException failure) {
      error(errors, StatusCode.IO_FAILURE);
      exit = 1;
    }
    StatusCode sessionClose = session.close();
    StatusCode clientClose = sessionClose.isOk() ? client.close() : sessionClose;
    if (exit == 0 && !clientClose.isOk()) {
      error(errors, clientClose);
      return 1;
    }
    return exit;
  }

  private static int runScript(
      RiverSession session,
      InputStream input,
      PrintStream output,
      PrintStream errors) throws IOException {
    InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
    char[] reads = new char[4 * 1024];
    char[] statement = new char[MAXIMUM_STATEMENT_CHARACTERS];
    QueryOpenResult opened = new QueryOpenResult();
    CommandResult command = new CommandResult();
    RowResult row = new RowResult();
    CommandResult closed = new CommandResult();
    int statementLength = 0;
    int read;
    while ((read = reader.read(reads)) >= 0) {
      for (int index = 0; index < read; index++) {
        char character = reads[index];
        if (character == ';') {
          if (containsSql(statement, statementLength)) {
            int exit = execute(
                session,
                statement,
                statementLength,
                opened,
                command,
                row,
                closed,
                output,
                errors);
            if (exit != 0) {
              return exit;
            }
          }
          statementLength = 0;
        } else {
          if (statementLength >= statement.length) {
            error(errors, StatusCode.RESOURCE_EXHAUSTED);
            return 1;
          }
          statement[statementLength++] = character;
        }
      }
    }
    return !containsSql(statement, statementLength)
        ? 0 : execute(
            session,
            statement,
            statementLength,
            opened,
            command,
            row,
            closed,
            output,
            errors);
  }

  private static int execute(
      RiverSession session,
      char[] characters,
      int length,
      QueryOpenResult opened,
      CommandResult command,
      RowResult row,
      CommandResult closed,
      PrintStream output,
      PrintStream errors) {
    String sql = new String(characters, 0, length);
    opened.reset();
    StatusCode status = session.beginQuery(sql, opened);
    if (status.isOk()) {
      return stream(opened.query(), row, closed, characters, output, errors);
    }
    if (status != StatusCode.INVALID_EXTERNAL_INPUT) {
      error(errors, status);
      return 1;
    }
    command.reset();
    status = session.execute(sql, command);
    if (!status.isOk()) {
      error(errors, status);
      return 1;
    }
    output.print("UPDATE\t");
    output.print(command.affectedRows());
    output.print('\t');
    output.println(command.commitSequence());
    return 0;
  }

  private static int stream(
      RiverQuery query,
      RowResult row,
      CommandResult closed,
      char[] textCharacters,
      PrintStream output,
      PrintStream errors) {
    for (int index = 0; index < query.columnCount(); index++) {
      if (index > 0) {
        output.print('\t');
      }
      output.print(query.columnName(index));
    }
    output.println();
    long rows = 0;
    StatusCode status;
    while ((status = query.next(row)).isOk() && row.isAvailable()) {
      for (int index = 0; index < row.columnCount(); index++) {
        if (index > 0) {
          output.print('\t');
        }
        if (row.isNull(index)) {
          output.print("NULL");
        } else if (row.isVarchar(index)) {
          int length = row.copyTextAt(index, textCharacters, 0);
          if (length < 0) {
            status = StatusCode.CORRUPTION;
            break;
          }
          for (int character = 0; character < length; character++) {
            output.print(textCharacters[character]);
          }
        } else {
          output.print(row.valueAt(index));
        }
      }
      if (!status.isOk()) {
        break;
      }
      output.println();
      rows++;
    }
    closed.reset();
    StatusCode close = query.close(closed);
    if (!status.isOk() || !close.isOk()) {
      error(errors, status.isOk() ? close : status);
      return 1;
    }
    output.print("ROWS\t");
    output.println(rows);
    return 0;
  }

  private static boolean containsSql(char[] characters, int length) {
    for (int index = 0; index < length; index++) {
      if (!Character.isWhitespace(characters[index])) {
        return true;
      }
    }
    return false;
  }

  private static int parsePort(String text) {
    if (text == null || text.isEmpty()) {
      return -1;
    }
    int port = 0;
    for (int index = 0; index < text.length(); index++) {
      char digit = text.charAt(index);
      if (digit < '0' || digit > '9') {
        return -1;
      }
      port = port * 10 + digit - '0';
      if (port > 65_535) {
        return -1;
      }
    }
    return port;
  }

  private static int usage(PrintStream errors) {
    errors.println("usage: river-sql PORT < script.sql");
    return 2;
  }

  private static void error(PrintStream errors, StatusCode status) {
    errors.print("ERROR\t");
    errors.println(status);
  }
}
