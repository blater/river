# `river` command-line contract

The unified command and help hierarchy are implemented by
[tic-ed14](tickets/tic-ed14.md) and [tic-9cfd](tickets/tic-9cfd.md).
[tic-a51d](tickets/tic-a51d.md) delivers single-file native packaging; Linux/Windows native validation
remains outstanding. This document owns the user-facing commands and behavior;
[ADR 0014](adr/0014-riverd-instance-security.md) owns their security and durable
lifecycle mechanisms. The [delivery plan](plans/riverd-standalone-server-plan.md)
maps implementation work.

## Commands

```text
river [CLIENT_PROPERTIES] < script.sql
river help [TOPIC...]
river version
river ps
river stop [HOST:PORT] [-D PATH|--datadir=PATH] [--timeout=DURATION]
river server
river server help
river server version
river server start [-D PATH|--datadir=PATH] [--port=PORT] [--ip=ADDRESS]
             [--maximum-connections=N] [--ready-file=PATH]
river server stop [HOST:PORT] [-D PATH|--datadir=PATH] [--timeout=DURATION]
river server ps
river server credentials renew [-D PATH|--datadir=PATH]
```

Start and offline maintenance select persistent data with `--datadir=PATH` or
`-D PATH`. `river ps` shows the HOST:PORT identifier accepted by `river stop`.
The data directory remains the instance's persistent identity; the endpoint
selects its current running process. There is no separate instance-name catalog.

## Help

- `river help`, `river -h`, and `river --help` print identical top-level help. Bare `river`
  runs SQL through the default instance configuration at
  `~/.river/default/security/client.properties`; a missing configuration suggests
  `river server start`. With no configuration, an interactive invocation or empty
  input prints usage successfully; supplied SQL returns an error.
- `river help <topic>`, `river -h <topic>`, and `river --help <topic>` are equivalent
  to the topic's trailing `-h` or `--help` form. Examples include `river help cli`,
  `river help server`, `river server start --help`, and
  `river help server credentials renew`.
- Group help lists immediate commands and availability. Leaf help lists every accepted
  option, aliases, value syntax, defaults, constraints, effects and a copyable example.
- Help and version exit successfully without connecting, opening a listener, reading
  credentials or changing instance files.

The default client reads semicolon-terminated SQL from stdin, writes tab-separated rows
and `ROWS` counts to stdout, and stops on the first error. An explicit absolute
`CLIENT_PROPERTIES` path selects another instance.

Help must explain the supported workflow in plain language, with copyable
examples. Unknown options, duplicate/conflicting options, extra arguments,
unlisted help placements, bare `credentials`, abbreviations and
combined short options are errors. Report a concise error and relevant help
before changing files or opening a listener.

## Start and defaults

| Option | Default | Meaning |
| --- | --- | --- |
| `--datadir=PATH` | `.river/default` under the user's home directory | Persistent instance directory |
| `--port=PORT` | `9191` | Decimal `0..65535`; zero selects an available port |
| `--ip=ADDRESS` | `127.0.0.1` | Optional loopback IP: `127.0.0.1` or `::1`, without brackets |
| `--maximum-connections=N` | `16` | Positive decimal connection limit that fits the configured resources |
| `--ready-file=PATH` | None | Optional readiness file for automation |

```sh
river server start
river server start --port=9192
river server start --datadir=/path/to/database --port=9192
river server start --port=0
river server start --ip=::1 --port=9192
```

`start` runs in the foreground. First start creates an instance; later starts
reopen its data and identity. An occupied port or competing owner is an error.
Relative paths resolve from the working directory. Wildcards, non-loopback IPs,
hostnames and malformed values are rejected. `--maximum-connections` accepts
`1..2147483647`; an addressable value that exceeds available configured resources
fails before mutation. There are no individual engine tuning flags.

On success, startup reports the resolved data directory, actual address and
port, PID and client settings path, plus a copyable `river stop HOST:PORT` command.
Its final readiness record is
`riverd_status=ready`. Automation may use `--ready-file`; an existing target
is never overwritten. The exact publication and failure rules remain in
[ADR 0014](adr/0014-riverd-instance-security.md#readiness-runtime-record-and-registry-formats).
A later server failure cannot retract an already observed readiness record.

## Connect with JDBC

First start creates the instance credentials automatically. TLS authenticates
the server; the instance token authenticates the client. The generated
`security/client.properties` identifies the endpoint, pinned certificate and
token file. Startup reports that configuration's path, never its secrets.

The distribution must explain how to obtain the River JDBC driver and include
one copyable connection example using this generated configuration. Users must
not need to construct certificates, configure a JVM trust store or implement an
authentication handshake. JDBC and CLI use the same client configuration owner.
The candidate accepts `jdbc:river:client-file:<absolute-path-to-client.properties>`.
The installed distribution includes the JDBC driver and its dependencies in `lib/`. Plain or unauthenticated connections are absent.

## Stop and list

```sh
river ps
river stop
river stop 127.0.0.1:9192
river stop '[::1]:9192' --timeout=60s
river stop --datadir=/path/to/database
```

`river stop` and `river server stop` are equivalent, as are `river ps` and
`river server ps`. Listing shows verified current-user local instances:

```text
SERVER           DEFAULT  DATA DIRECTORY
127.0.0.1:9191    yes      /Users/alex/.river/default
127.0.0.1:9192    no       /Users/alex/databases/demo
```

Copy the SERVER value into `river stop HOST:PORT`. IPv6 uses brackets;
`localhost:PORT` selects the IPv4 loopback endpoint. Bare `stop` selects
`~/.river/default`, even when other servers are running. An explicit data
directory and an endpoint cannot be combined. An endpoint with no running server
prints a message and exits successfully. An ambiguous endpoint returns an error. Remote administration
is not supported.

Stop requests
graceful shutdown from the verified instance owner and waits for completion.
The default timeout is `30s`. A duration is a positive decimal integer followed
by `ms`, `s` or `m`, fitting signed-long milliseconds. Fractions, signs, zero,
whitespace, missing units and overflow are invalid.

A timeout returns an error and never escalates to force-killing. An absent or already stopped instance exits successfully.
Invalid metadata or a mismatched active owner returns a clear error. The command does not select or
signal a process by PID. Ctrl-C and platform shutdown signals use the same
shutdown path: close the listener before the database and preserve committed
data for restart.

`ps` lists verified instances registered by the current user, sorted by data
directory, with the endpoint, default-instance marker and data directory. An empty list exits
successfully and suggests `river server start`. Registrations for absent or stopped instances are skipped. Invalid or unreadable
records produce a concise warning. Records are preserved.

Successful stop prints a confirmation followed by status records:

```text
Stopped River server 127.0.0.1:9191.
riverd_datadir=<normalized-absolute-path>
riverd_pid=<positive-decimal-long>
riverd_status=OK
```

## Offline maintenance

`river server credentials renew` is planned and currently reports that it is
unavailable. It will replace credentials for a stopped instance and accept the
same data-directory option. After renewal,
restart and use the newly published client settings; old credentials no longer
authenticate. The command does not delete database data. SQL/security audit
collection and an audit archive command are deferred; they are not part of the
current CLI contract.

These are later operational deliveries. The first usable-server milestone is
installed start, authenticated JDBC, graceful shutdown and persistent restart.

## Version

`river version` and `river server version` print:

```text
riverd_version=<distribution-version>
riverd_contract=riverd-v1
riverd_protocol=river-v4
riverd_status=OK
```

## Exit codes and errors

Ordinary command termination has three public exit classes:

| Exit | Native outcome | Meaning |
| --- | --- | --- |
| 0 | `OK` | Help, version, listing including an empty list, successful stop or offline operation, or a foreground server that shut down cleanly. |
| 2 | `INVALID_EXTERNAL_INPUT` | Invalid command syntax or option value, detected before mutation. |
| 1 | Named non-`OK` `StatusCode` | Startup, lifecycle, authentication/authorization, filesystem, I/O, or shutdown failure. |

Termination by an operating-system signal retains the JVM/platform signal exit
code (for example, 143 for SIGTERM on Unix), even when the shutdown hook completes
cleanly. Automation must distinguish this from ordinary command termination.

An exit-1/2 command writes a concise diagnostic followed by exactly
`riverd_status_code=<StatusCode.stableCode()>` and final
`riverd_status=<StatusCode-name>` records to standard error. A failure known
before the selected readiness commit emits no successful readiness. A command
whose readiness observation is already irrevocable may later emit these
failure records and exit 1 under ADR 0014’s post-commit rules; it does not emit
a second or retracting readiness record. Human text is diagnostic only and is
not a second status contract.

## Delivery boundary

Required platforms are macOS/APFS, Linux/ext4 and XFS, and Windows/NTFS.
The distribution runs without Gradle, a source checkout or manual classpaths.
Validate the shipped workflow: help, start on a chosen port, connect, commit,
stop, restart and read the committed data.

PostgreSQL wire compatibility, remote listening, multiple SQL principals,
background daemonisation, service installation, remote administration and
benchmark orchestration are outside this delivery. There are no TLS-disable,
no-authentication, credential-by-value or secret environment-variable options.
Keep implementation and review focused on this usable workflow and concrete
correctness, security and platform requirements.
