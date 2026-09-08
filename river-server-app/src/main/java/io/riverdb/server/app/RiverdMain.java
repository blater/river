package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installed riverd process entry point. */
public final class RiverdMain {
  private static final AtomicBoolean failureReported = new AtomicBoolean();

  private RiverdMain() { }

  public static void main(String[] arguments) {
    System.exit(run(arguments));
  }

  static int run(String[] arguments) {
    RiverdCommandResult command = new RiverdCommandResult();
    StatusCode status = RiverdCommandParser.parse(arguments, command);
    if (!status.isOk()) {
      System.err.print(RiverdCommandHelp.brief(command.helpTopic()));
      reportFailure(status, command.diagnostic());
      return command.exitCode(status);
    }
    switch (command.command()) {
      case BRIEF_HELP -> System.out.print(RiverdCommandHelp.brief(command.helpTopic()));
      case FULL_HELP -> System.out.print(RiverdCommandHelp.full(command.helpTopic()));
      case VERSION -> System.out.print(RiverdCommandHelp.version(RiverDaemonVersion.value()));
      case START -> {
        StatusCode start = RiverdForeground.run(command, Path.of(System.getProperty("user.home")));
        if (!start.isOk()) reportFailure(start, "riverd could not complete the requested lifecycle");
        return command.exitCode(start);
      }
      default -> {
        reportFailure(StatusCode.FEATURE_NOT_SUPPORTED,
            "riverd command is unavailable in this milestone");
        return 1;
      }
    }
    return 0;
  }

  /** Main and the shutdown hook share one final failure record, including signal-driven shutdown. */
  static void reportFailure(StatusCode status, String diagnostic) {
    if (!failureReported.compareAndSet(false, true)) return;
    if (diagnostic != null) System.err.println(diagnostic);
    System.err.println("riverd_status_code=" + status.stableCode());
    System.err.println("riverd_status=" + status);
    System.err.flush();
  }
}
