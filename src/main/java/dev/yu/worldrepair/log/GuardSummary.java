package dev.yu.worldrepair.log;

import dev.yu.worldrepair.guard.ErrorSignature;

public record GuardSummary(ErrorSignature signature, long suppressed) {
}
