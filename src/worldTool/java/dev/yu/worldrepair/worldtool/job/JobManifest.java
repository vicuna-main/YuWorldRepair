package dev.yu.worldrepair.worldtool.job;

public record JobManifest(
        int schemaVersion,
        String jobId,
        String createdAt,
        String updatedAt,
        JobState state,
        String worldRoot,
        String worldFingerprint,
        String adapterId,
        String toolVersion,
        String javaVersion,
        String minecraftVersion,
        String neoForgeVersion,
        String youerVersion,
        String iceAndFireSha256,
        int regionCount,
        int chunkCount,
        int totalTargets,
        int addressableTargets,
        int blockedTargets,
        String detail
) {
    public JobManifest withState(JobState next, String now, String nextDetail) {
        return new JobManifest(
                schemaVersion,
                jobId,
                createdAt,
                now,
                next,
                worldRoot,
                worldFingerprint,
                adapterId,
                toolVersion,
                javaVersion,
                minecraftVersion,
                neoForgeVersion,
                youerVersion,
                iceAndFireSha256,
                regionCount,
                chunkCount,
                totalTargets,
                addressableTargets,
                blockedTargets,
                nextDetail
        );
    }
}
