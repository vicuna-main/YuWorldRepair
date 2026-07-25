package dev.yu.worldrepair.compat;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespacePaletteCompatibilityTest {
    @Test
    void minecraftCodecAcceptsDuplicateAirPaletteEntriesAfterReplacement() {
        CompoundTag packed = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(state("minecraft:air"));
        palette.add(state("minecraft:air"));
        palette.add(state("minecraft:stone"));
        packed.put("palette", palette);
        SimpleBitStorage storage = new SimpleBitStorage(4, 4_096);
        storage.set(0, 1);
        storage.set(1, 2);
        packed.put("data", new LongArrayTag(storage.getRaw()));

        DataResult<PalettedContainer<BlockState>> decoded =
                PalettedContainer.codecRW(
                        Block.BLOCK_STATE_REGISTRY,
                        BlockState.CODEC,
                        PalettedContainer.Strategy.SECTION_STATES,
                        Blocks.AIR.defaultBlockState()
                ).parse(NbtOps.INSTANCE, packed);
        assertTrue(decoded.result().isPresent(), () -> decoded.error()
                .map(Object::toString)
                .orElse("unknown palette codec error"));
        PalettedContainer<BlockState> container = decoded.result().orElseThrow();
        assertEquals(Blocks.AIR.defaultBlockState(), container.get(0, 0, 0));
        assertEquals(Blocks.STONE.defaultBlockState(), container.get(1, 0, 0));
    }

    private static CompoundTag state(String name) {
        CompoundTag state = new CompoundTag();
        state.put("Name", StringTag.valueOf(name));
        return state;
    }
}
