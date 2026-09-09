package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;

/** Caller-owned parse result and side-effect-free start configuration. */
public final class RiverdCommandResult {
  private RiverdCommand command;
  private Path datadir;
  private int port;
  private String ip;
  private int maximumConnections;
  private Path readyFile;
  private long timeoutMillis;
  private String server;
  private String helpTopic;
  private String diagnostic;

  public void reset() {
    command = null;
    datadir = null;
    port = 0;
    ip = null;
    maximumConnections = 0;
    readyFile = null;
    timeoutMillis = 0;
    server = null;
    helpTopic = null;
    diagnostic = null;
  }

  public RiverdCommand command() { return command; }
  public Path datadir() { return datadir; }
  public int port() { return port; }
  public String ip() { return ip; }
  public int maximumConnections() { return maximumConnections; }
  public Path readyFile() { return readyFile; }
  public long timeoutMillis() { return timeoutMillis; }
  public String server() { return server; }
  public String helpTopic() { return helpTopic; }
  public String diagnostic() { return diagnostic; }

  public int exitCode(StatusCode status) {
    if (status == null || status.isOk()) return 0;
    return status == StatusCode.INVALID_EXTERNAL_INPUT ? 2 : 1;
  }

  void complete(RiverdCommand value) { command = value; }
  void setDatadir(Path value) { datadir = value; }
  void setPort(int value) { port = value; }
  void setIp(String value) { ip = value; }
  void setMaximumConnections(int value) { maximumConnections = value; }
  void setReadyFile(Path value) { readyFile = value; }
  void setTimeoutMillis(long value) { timeoutMillis = value; }
  void setServer(String value) { server = value; }
  void setHelpTopic(String value) { helpTopic = value; }
  void fail(String value) { diagnostic = value; }
}
