package io.riverdb.testkit.io;

import io.riverdb.platform.fault.FaultPoint;

/** Named fault boundaries used by the in-memory durable-file model. */
public record FileFaultPoints(
    FaultPoint open,
    FaultPoint read,
    FaultPoint write,
    FaultPoint force,
    FaultPoint size,
    FaultPoint truncate,
    FaultPoint close,
    FaultPoint crash,
    FaultPoint restart) {}
