# River tickets

This directory contains River's source-controlled execution backlog. The
repository-root [`ticket.yaml`](../../ticket.yaml) configures `tk` to read and
write ticket Markdown files here, including when it is invoked from a nested
repository directory.

Tickets record epics, stories, investigations, dependencies, ownership,
delivery state, and links to evidence. They do not replace architectural or
semantic authorities:

- [`docs/plans/`](../plans/) contains implementation and performance plans;
- [`docs/adr/`](../adr/) contains accepted durable decisions;
- [`docs/performance-checkpoints.md`](../performance-checkpoints.md) contains
  accepted performance checkpoint history; and
- [`manifesto.md`](../../manifesto.md) and [`AGENTS.md`](../../AGENTS.md) define
  the working principles and operational contract.

Ticket descriptions should link to those sources instead of copying their
requirements. Ticket files remain in Git after completion so dependencies,
delivery history, and rollback evidence remain reproducible.

River requires feature branches to identify their ticket and delivered commits
to carry an exact `Ticket: <ticket-id>` Git trailer. Code and documentation
tickets record that immutable commit before closure. Evidence investigations
record a commit or evidence reference, and performance deliveries also record
their accepted checkpoint tag. The repository-root `ticket.yaml` contains the
enforced branch and delivery policy.
