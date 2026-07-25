package dev.yu.worldrepair.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceIdExtractorTest {
    @Test
    void prefersRegistryValueAfterResourceKeyDescription() {
        String error = "Unknown registry key in ResourceKey[minecraft:root / minecraft:item]: "
                + "kaleidoscope_tavern:sunset_glow missed input: {id:\"minecraft:stone\"}";
        long bounds = ResourceIdExtractor.findBounds(error);

        assertEquals("kaleidoscope_tavern:sunset_glow", ResourceIdExtractor.materialize(error, bounds));
    }

    @Test
    void extractsDirectAttachmentId() {
        String error = "oldmod:player_state";
        assertEquals(
                error,
                ResourceIdExtractor.materialize(error, ResourceIdExtractor.findBounds(error))
        );
    }

    @Test
    void scanIsHardBounded() {
        String input = "x".repeat(ResourceIdExtractor.MAX_SCAN_CHARS + 1_000) + "late:id";
        assertEquals(
                "unknown",
                ResourceIdExtractor.materialize(input, ResourceIdExtractor.findBounds(input))
        );
    }
}
