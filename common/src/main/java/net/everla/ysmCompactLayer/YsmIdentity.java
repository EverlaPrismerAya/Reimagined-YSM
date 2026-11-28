package net.everla.ysmCompactLayer;

import java.nio.file.Path;

/** Loader identity is independent of historical resources, save data and wire IDs. */
public final class YsmIdentity {
    public static final String MOD_ID = "rysm";
    public static final String OFFICIAL_MOD_ID = "yes_steve_model";
    public static final String RESOURCE_NAMESPACE = "rysm";
    public static final String DATA_NAMESPACE = "yes_steve_model";
    public static final Path MODEL_DIRECTORY = Path.of("config", "yes_steve_model");

    private YsmIdentity() {}
}
