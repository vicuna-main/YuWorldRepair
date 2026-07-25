package dev.yu.worldrepair.worldtool.namespace;

import dev.yu.worldrepair.worldtool.job.JobState;

public record NamespaceJobManifest(
        int schemaVersion,
        String jobId,
        String createdAt,
        String updatedAt,
        JobState state,
        String worldRoot,
        String worldFingerprint,
        String namespace,
        NamespacePolicy.Mode mode,
        String registrySnapshotSha256,
        int regionsScanned,
        int chunksScanned,
        int totalTargets,
        int coverageGaps,
        String detail
) {
    public static final int SCHEMA_VERSION = 1;

    public NamespaceJobManifest withState(JobState next, String time, String nextDetail) {
        return new NamespaceJobManifest(
                schemaVersion,
                jobId,
                createdAt,
                time,
                next,
                worldRoot,
                worldFingerprint,
                namespace,
                mode,
                registrySnapshotSha256,
                regionsScanned,
                chunksScanned,
                totalTargets,
                coverageGaps,
                nextDetail
        );
    }
}
