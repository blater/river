# River protocol slice

This module currently defines the bounded wire contract consumed by the
loopback TCP server. It is an unreleased, pre-V1 contract: River may replace it
directly as the SQL value model, batching, authorization, and cancellation
capabilities grow. No mixed-version or migration machinery is promised before
V1.

Frames are big-endian and contain a 32-byte header: magic, protocol version,
message type, flags, positive request ID, payload length, and a zero reserved
field. Payloads are limited to 16 KiB before use. Requests are ordered and one
session may own at most one active query. Each `FETCH` grants credit for one
bounded row, so the server does not buffer unread results.

Responses carry the stable River status code, result flags, affected-row and
column counts, commit sequence, key, rows returned, and at most eight `BIGINT`
values. Encoding writes directly into caller-owned output storage; decoding
uses caller-owned reusable carriers. Statement decoding creates the one
`String` currently required by the embedded engine API, while fetch and
response paths are allocation-free after warmup.

Authenticated connections negotiate protocol version inside TLS 1.3, verify
the server hostname, and use a fresh server challenge plus TLS exporter keying
material in an HMAC proof. Raw tokens are never placed in protocol frames. The
server stores only the token hash; proof buffers and channel-binding material
are erased after authentication. Tokens are therefore required to be random,
high-entropy credentials rather than human passwords.

The production server admits connections into a fixed number of preallocated
slots. Each admitted connection owns its frame buffers and a virtual thread;
an idle connection therefore cannot serialize other sessions. Connections
above the configured cap are closed before a database session is created.

The only production listeners remain deliberately bound to the operating
system's loopback address. Non-loopback service remains unavailable until
authorization is implemented.
