package io.riverdb.testkit.io;

import io.riverdb.platform.fault.FaultPoint;

/** Before/after fault boundaries for every atomic-install half-step. */
public record AtomicInstallFaultPoints(
    FaultPoint tempCreateBefore,
    FaultPoint tempCreateAfter,
    FaultPoint tempWriteBefore,
    FaultPoint tempWriteAfter,
    FaultPoint tempForceBefore,
    FaultPoint tempForceAfter,
    FaultPoint replaceBefore,
    FaultPoint replaceAfter,
    FaultPoint directoryForceBefore,
    FaultPoint directoryForceAfter,
    FaultPoint reopenVerifyBefore,
    FaultPoint reopenVerifyAfter) {}
