/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import net.minecraftforge.common.ForgeConfigSpec;

final class AdapterConfig {
    enum Physics { NONE, BOUNDARY_ONLY, COMPATIBLE }
    enum Progression { CENTER_OUT, AS_FAST_AS_POSSIBLE }
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.DoubleValue MILLIS_PER_TICK;
    static final ForgeConfigSpec.IntValue VISITS_PER_TICK;
    static final ForgeConfigSpec.IntValue WRITES_PER_TICK;
    static final ForgeConfigSpec.IntValue PACKET_BYTES;
    static final ForgeConfigSpec.IntValue MAX_PENDING_BYTES;
    static final ForgeConfigSpec.IntValue MAX_IN_FLIGHT_CHUNKS;
    static final ForgeConfigSpec.IntValue READ_AHEAD_CHUNKS;
    static final ForgeConfigSpec.IntValue MAX_RADIUS;
    static final ForgeConfigSpec.IntValue MAX_OPERATIONS;
    static final ForgeConfigSpec.DoubleValue RESISTANCE_LIMIT;
    static final ForgeConfigSpec.DoubleValue ENTITY_DAMAGE;
    static final ForgeConfigSpec.BooleanValue PROTECT_ALL_CLAIMS;
    static final ForgeConfigSpec.EnumValue<Physics> PHYSICS;
    static final ForgeConfigSpec.EnumValue<Progression> PROGRESSION;
    static final ForgeConfigSpec.IntValue SHELL_WIDTH;
    static final ForgeConfigSpec.BooleanValue BULK_SECTIONS;
    static final ForgeConfigSpec.BooleanValue EXACT_NEW_CHUNK_BIOMES;
    static final ForgeConfigSpec.IntValue IO_THREADS;
    static final ForgeConfigSpec.IntValue PREPARATION_THREADS;
    static final ForgeConfigSpec.EnumValue<org.voxelregion.propagation.FrozenOcclusion.Mode> PROPAGATION;
    static final ForgeConfigSpec.IntValue OCCLUSION_CELL;
    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("budget");
        IO_THREADS = b.comment("Independent native region-file lanes and detached compression workers. Writes within one region stay ordered.")
                .defineInRange("io_threads", Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors())), 1, 32);
        PREPARATION_THREADS = b.comment("Detached cleared-terrain preparation workers; never access mutable live chunks.")
                .defineInRange("preparation_threads", Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors())), 1, 32);
        MILLIS_PER_TICK = b.comment("One shared cooperative main-thread budget across all dimensions and operations. A single native callback/load cannot be preempted.")
                .defineInRange("millis_per_tick", 40.0, 0.1, 40.0);
        VISITS_PER_TICK = b.comment("Bulk preparation work units: palette entries for full clears, palette entries plus packed storage/mask words for packed selections, or 4096 voxel inspections for ordinary mixed encoding. Wall-clock budget still applies. Historical name retained for config compatibility.")
                .defineInRange("voxel_visits_per_tick", 262144, 1, 1_000_000);
        WRITES_PER_TICK = b.comment("Commit work units: one per replaced section or native block write. A whole-chunk transaction is indivisible and can exceed the remaining units.")
                .defineInRange("block_writes_per_tick", 262144, 1, 1_000_000);
        PACKET_BYTES = b.defineInRange("packet_bytes_per_tick", 8_388_608, 1024, 67_108_864);
        MAX_PENDING_BYTES = b.defineInRange("maximum_pending_metadata_bytes", 134_217_728, 1_048_576, 268_435_456);
        MAX_IN_FLIGHT_CHUNKS = b.comment("Global maximum of currently written and light/save/packet-pending chunks. Their tickets and exclusive reservations stay held until acknowledgement.")
                .defineInRange("maximum_in_flight_chunks", 4096, 1, 4096);
        READ_AHEAD_CHUNKS = b.comment("Native asynchronous read/generation window for center-out operations with generation enabled. Zero disables prefetch. Counts against the global retained-chunk limit.")
                .defineInRange("read_ahead_chunks", 1024, 0, 2048);
        b.pop().push("operations");
        MAX_RADIUS = b.defineInRange("maximum_radius", 2000, 1, 10000);
        MAX_OPERATIONS = b.defineInRange("maximum_pending_operations", 16, 1, 128);
        PROGRESSION = b.comment("New CENTER_OUT operations process complete chunks in increasing horizontal distance from the center. AS_FAST_AS_POSSIBLE uses region locality. Existing persisted shell operations retain their original meaning.")
                .defineEnum("progression", Progression.CENTER_OUT);
        SHELL_WIDTH = b.comment("Legacy persisted shell operation setting; new operations commit complete chunks and do not split them into shells.")
                .defineInRange("shell_width_blocks", 32, 1, 128);
        BULK_SECTIONS = b.comment("Use audited whole-section replacement when geometry, palette, protection and host hooks prove it safe.")
                .define("bulk_sections", true);
        EXACT_NEW_CHUNK_BIOMES = b.comment("Only affects never-generated chunks materialized as cleared terrain. False samples the native biome at the chunk center near sea level and fills the column uniformly, omitting underground biome bands. True computes every native quart sample. Existing chunk biomes are always preserved.")
                .define("exact_new_chunk_biomes", false);
        PROPAGATION = b.comment("NONE clears only by geometry. Other modes first capture and durably freeze all blocker tags; incomplete or changed snapshots pause safely.")
                .defineEnum("propagation", org.voxelregion.propagation.FrozenOcclusion.Mode.NONE);
        OCCLUSION_CELL = b.comment("Macro blocker cell size: 4, 8 or 16; other values are rejected before accepting an operation.")
                .defineInRange("occlusion_cell_size", 4, 4, 16);
        RESISTANCE_LIMIT = b.comment("Keep blocks with context-sensitive explosion resistance at or above this value; unbreakable blocks and the protected tag are always kept.")
                .defineInRange("protected_resistance", 6000.0, 0.0, 3.4e38);
        ENTITY_DAMAGE = b.comment("0 disables entity damage. Positive values apply linear spherical damage once, to currently loaded entities only. Protected claims are always excluded; no claim bypass.")
                .defineInRange("entity_damage_at_center", 0.0, 0.0, 2048.0);
        PROTECT_ALL_CLAIMS = b.comment("Otherwise follow FTB Chunks canExplosionsDamageTerrain(). Claims never block propagation in this baseline.")
                .define("protect_all_claimed_chunks", false);
        PHYSICS = b.comment("NONE suppresses neighbor physics. BOUNDARY_ONLY notifies retained adjacent states after native writes. COMPATIBLE uses flags 3. Indirect vanilla physics may spread beyond the direct clear volume in both physics-enabled modes.")
                .defineEnum("physics", Physics.NONE);
        b.pop();
        SPEC = b.build();
    }
    private AdapterConfig() {}
}
