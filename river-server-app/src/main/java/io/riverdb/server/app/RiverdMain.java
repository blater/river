package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Callable owner for the server command and its lifecycle. */
public final class RiverdMain {
  private static final AtomicBoolean failureReported = new AtomicBoolean();

  private RiverdMain() { }

  static int run(String[] arguments, PrintStream output, PrintStream errors) {
    RiverdCommandResult command = new RiverdCommandResult();
    StatusCode status = RiverdCommandParser.parse(arguments, command);
    if (!status.isOk()) {
      errors.print(RiverdCommandHelp.brief(command.helpTopic()));
      reportCommandFailure(status, command.diagnostic(), errors);
      return command.exitCode(status);
    }
    switch (command.command()) {
      case BRIEF_HELP -> output.print(RiverdCommandHelp.brief(command.helpTopic()));
      case FULL_HELP -> output.print(RiverdCommandHelp.full(command.helpTopic()));
      case VERSION -> output.print(RiverdCommandHelp.version(RiverDaemonVersion.value()));
      case START -> {
        StatusCode start = RiverdForeground.run(command, Path.of(System.getProperty("user.home")));
        if (!start.isOk()) {
          reportFailure(start, "river server could not complete the requested lifecycle", errors);
        }
        return command.exitCode(start);
      }
      default -> {
        reportCommandFailure(StatusCode.FEATURE_NOT_SUPPORTED,
            "river server command is unavailable in this milestone", errors);
        return 1;
      }
    }
    return 0;
  }

  /** Main and the shutdown hook share one final failure record, including signal-driven shutdown. */
  static void reportFailure(StatusCode status, String diagnostic) {
    reportFailure(status, diagnostic, System.err);
  }

  private static void reportCommandFailure(
      StatusCode status, String diagnostic, PrintStream errors) {
    if (diagnostic != null) errors.println(diagnostic);
    errors.println("riverd_status_code=" + status.stableCode());
    errors.println("riverd_status=" + status);
    errors.flush();
  }

  private static void reportFailure(StatusCode status, String diagnostic, PrintStream errors) {
    if (!failureReported.compareAndSet(false, true)) return;
    if (diagnostic != null) errors.println(diagnostic);
    errors.println("riverd_status_code=" + status.stableCode());
    errors.println("riverd_status=" + status);
    errors.flush();
  }
}
