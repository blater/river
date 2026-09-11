package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
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
      errors.print(RiverdCommandHelp.render(
          command.helpTopic() == null ? "server" : command.helpTopic()));
      reportCommandFailure(status, command.diagnostic(), errors);
      return command.exitCode(status);
    }
    switch (command.command()) {
      case HELP -> output.print(RiverdCommandHelp.render(command.helpTopic()));
      case VERSION -> output.print(RiverdCommandHelp.version(RiverDaemonVersion.value()));
      case START -> {
        StatusCode start = RiverdForeground.run(command, Path.of(System.getProperty("user.home")));
        if (!start.isOk()) {
          reportFailure(start, "river server could not complete the requested lifecycle", errors);
        }
        return command.exitCode(start);
      }
      case STOP, PS -> {
        return runOperation(command, output, errors);
      }
      default -> {
        reportCommandFailure(StatusCode.FEATURE_NOT_SUPPORTED,
            "river server command is unavailable in this milestone", errors);
        return 1;
      }
    }
    return 0;
  }

  private static int runOperation(
      RiverdCommandResult command, PrintStream output, PrintStream errors) {
    RiverDaemonFileSystemResult filesystem = new RiverDaemonFileSystemResult();
    StatusCode status = RiverDaemonFileSystems.current(filesystem);
    Path home = Path.of(System.getProperty("user.home"));
    if (status.isOk() && command.command() == RiverdCommand.PS) {
      status = RiverDaemonTargets.list(filesystem.fileSystem(), home, output, errors);
    } else if (status.isOk()) {
      RiverDaemonTarget.Result result = new RiverDaemonTarget.Result();
      status = RiverDaemonTargets.resolve(filesystem.fileSystem(), home,
          command.datadir(), command.server(), result, errors);
      if (status.isOk()) {
        RiverDaemonTarget target = result.target();
        if (target == null) {
          String selected = command.server() != null ? command.server()
              : command.datadir() != null ? command.datadir().toString() : "the default instance";
          output.println("No River server is running for " + selected + ".");
          return 0;
        }
        status = RiverDaemonStopClient.request(target, command.timeoutMillis());
        StatusCode close = target.close();
        if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
        if (status.isOk()) {
          String server = target.runtime == null ? target.datadir.toString()
              : RiverDaemonEndpoint.of(target.runtime.address, target.runtime.port).toString();
          output.println("Stopped River server " + server + ".");
          output.println("riverd_datadir=" + target.datadir);
          output.println("riverd_pid=" + target.owner.pid);
          output.println("riverd_status=OK");
        }
      }
    }
    if (!status.isOk()) {
      String diagnostic = command.command() == RiverdCommand.PS
          ? "Could not list River servers."
          : status == StatusCode.TIMEOUT
              ? "Timed out waiting for shutdown; the server may still be stopping."
              : "Could not stop the selected server. Use `river ps` to list running instances.";
      reportCommandFailure(status, diagnostic, errors);
    }
    return command.exitCode(status);
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
    reportCommandFailure(status, diagnostic, errors);
  }
}
