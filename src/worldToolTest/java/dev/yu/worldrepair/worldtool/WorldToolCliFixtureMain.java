package dev.yu.worldrepair.worldtool;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Creates privacy-free input for an end-to-end invocation of the packaged CLI.
 */
public final class WorldToolCliFixtureMain {
    private WorldToolCliFixtureMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one absolute output directory");
        }
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!output.isAbsolute() || java.nio.file.Files.exists(output)) {
            throw new IllegalArgumentException("Fixture output must be a new absolute directory");
        }
        Path world = WorldToolFixture.createWorld(output);
        WorldToolFixture.writeEntityRegion(
                world,
                "dimensions/yuworldrepair/test/entities",
                -65,
                97,
                2,
                List.of(WorldToolFixture.entity(
                        "minecraft:chicken",
                        UUID.fromString("01234567-89ab-cdef-8123-456789abcdef"),
                        true,
                        true,
                        List.of()
                ))
        );
        System.out.println(world);
    }
}
