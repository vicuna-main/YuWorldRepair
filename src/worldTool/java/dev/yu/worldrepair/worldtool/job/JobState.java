package dev.yu.worldrepair.worldtool.job;

public enum JobState {
    SCANNED,
    PREPARED,
    APPLYING,
    APPLIED,
    VERIFIED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED
}
