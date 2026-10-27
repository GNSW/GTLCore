package org.gtlcore.gtlcore.integration.ae2.wireless;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class WirelessAeSavedData extends SavedData {

    private static final String DATA_NAME = "gtlcore_wireless_ae_networks";
    private static final String TAG_PLAYER_DATA = "gtlcore_wireless_ae";
    private static final String TAG_NETWORKS = "networks";
    private static final String TAG_FAVORITE_NETWORK = "favorite_network";
    private static final String TAG_LEGACY_FAVORITE_MIGRATED = "legacy_favorite_migrated";
    private static final String TAG_FREQUENCY = "frequency";
    private static final String TAG_CORE = "core";
    private static final String TAG_OWNER = "owner";
    private static final String TAG_NAME = "name";
    private static final String TAG_MEMBERS = "members";
    private static final String TAG_DIMENSION = "dimension";
    private static final String TAG_X = "x";
    private static final String TAG_Y = "y";
    private static final String TAG_Z = "z";
    private static final String TAG_SIDE = "side";
    private static final String TAG_MEMBER_REVISION = "member_revision";

    private final Map<UUID, NetworkRecord> networks = new HashMap<>();
    private final Map<MemberKey, UUID> memberNetworks = new HashMap<>();
    private long memberRevision;
    private UUID legacyFavoriteNetwork;

    public static WirelessAeSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                WirelessAeSavedData::load,
                WirelessAeSavedData::new,
                DATA_NAME);
    }

    public static WirelessAeSavedData load(CompoundTag tag) {
        WirelessAeSavedData data = new WirelessAeSavedData();
        if (tag.hasUUID(TAG_FAVORITE_NETWORK)) {
            data.legacyFavoriteNetwork = tag.getUUID(TAG_FAVORITE_NETWORK);
        }

        ListTag networksTag = tag.getList(TAG_NETWORKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < networksTag.size(); i++) {
            CompoundTag networkTag = networksTag.getCompound(i);
            if (!networkTag.hasUUID(TAG_FREQUENCY)) {
                continue;
            }

            UUID frequency = networkTag.getUUID(TAG_FREQUENCY);
            NetworkRecord record = data.network(frequency);
            record.core = readGlobalPos(networkTag.getCompound(TAG_CORE));
            if (networkTag.hasUUID(TAG_OWNER)) {
                record.owner = networkTag.getUUID(TAG_OWNER);
            }
            record.name = networkTag.getString(TAG_NAME);
            record.memberRevision = networkTag.getLong(TAG_MEMBER_REVISION);

            ListTag membersTag = networkTag.getList(TAG_MEMBERS, Tag.TAG_COMPOUND);
            for (int j = 0; j < membersTag.size(); j++) {
                MemberKey member = readMemberKey(membersTag.getCompound(j));
                if (member != null) {
                    record.members.add(member);
                } else {
                    data.setDirty();
                }
            }
        }
        // Invalid records must not win ownership over valid networks during duplicate repair.
        if (data.networks.values().removeIf(record -> record.core == null)) {
            data.setDirty();
        }
        data.rebuildMemberIndex();
        if (data.legacyFavoriteNetwork != null && !data.networks.containsKey(data.legacyFavoriteNetwork)) {
            data.legacyFavoriteNetwork = null;
            data.setDirty();
        }
        return data;
    }

    /** Rebuild derived state; deterministic UUID order resolves ambiguous legacy memberships. */
    private void rebuildMemberIndex() {
        this.memberNetworks.clear();
        int conflicts = 0;
        List<UUID> frequencies = new ArrayList<>(this.networks.keySet());
        frequencies.sort(Comparator.naturalOrder());
        for (UUID frequency : frequencies) {
            var members = this.networks.get(frequency).members.iterator();
            while (members.hasNext()) {
                MemberKey member = members.next();
                if (this.memberNetworks.putIfAbsent(member, frequency) != null) {
                    members.remove();
                    conflicts++;
                }
            }
        }
        if (conflicts > 0) {
            setDirty();
            GTLCore.LOGGER.warn("Repaired {} duplicate wireless ME memberships using UUID order", conflicts);
        }
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        if (this.legacyFavoriteNetwork != null && this.networks.containsKey(this.legacyFavoriteNetwork)) {
            tag.putUUID(TAG_FAVORITE_NETWORK, this.legacyFavoriteNetwork);
        } else {
            tag.remove(TAG_FAVORITE_NETWORK);
        }

        ListTag networksTag = new ListTag();
        for (Map.Entry<UUID, NetworkRecord> entry : this.networks.entrySet()) {
            NetworkRecord record = entry.getValue();
            if (record.core == null) {
                continue;
            }

            CompoundTag networkTag = new CompoundTag();
            networkTag.putUUID(TAG_FREQUENCY, entry.getKey());
            networkTag.put(TAG_CORE, writeGlobalPos(record.core));
            if (record.owner != null) {
                networkTag.putUUID(TAG_OWNER, record.owner);
            }
            networkTag.putString(TAG_NAME, record.name);
            networkTag.putLong(TAG_MEMBER_REVISION, record.memberRevision);

            ListTag membersTag = new ListTag();
            for (MemberKey member : record.members) {
                membersTag.add(writeMemberKey(member));
            }
            networkTag.put(TAG_MEMBERS, membersTag);
            networksTag.add(networkTag);
        }
        tag.put(TAG_NETWORKS, networksTag);
        return tag;
    }

    public void setCore(UUID frequency, GlobalPos core) {
        NetworkRecord record = network(frequency);
        if (!core.equals(record.core)) {
            record.core = core;
            setDirty();
        }
    }

    /** A scoped, stable snapshot for manipulator undo; revision counters are derived state. */
    public CompoundTag captureNetwork(UUID frequency) {
        CompoundTag tag = new CompoundTag();
        NetworkRecord record = networks.get(frequency);
        if (record == null) return tag;
        tag.putBoolean("Present", true);
        if (record.core != null) tag.put(TAG_CORE, writeGlobalPos(record.core));
        if (record.owner != null) tag.putUUID(TAG_OWNER, record.owner);
        tag.putString(TAG_NAME, record.name);
        ListTag members = new ListTag();
        record.members.stream().map(WirelessAeSavedData::writeMemberKey)
                .sorted(Comparator.comparing(CompoundTag::toString)).forEach(members::add);
        tag.put(TAG_MEMBERS, members);
        tag.putBoolean("LegacyFavorite", frequency.equals(legacyFavoriteNetwork));
        return tag;
    }

    /** Refuse to reclaim members that another network acquired after a core was removed. */
    public boolean canRestoreNetwork(UUID frequency, CompoundTag before, CompoundTag expected) {
        Set<MemberKey> expectedMembers = new HashSet<>();
        for (Tag value : expected.getList(TAG_MEMBERS, Tag.TAG_COMPOUND)) expectedMembers.add(readMemberKey((CompoundTag) value));
        for (Tag value : before.getList(TAG_MEMBERS, Tag.TAG_COMPOUND)) {
            MemberKey member = readMemberKey((CompoundTag) value);
            UUID current = getExactMemberNetwork(member);
            if (!java.util.Objects.equals(current, expectedMembers.contains(member) ? frequency : null)) return false;
        }
        return true;
    }

    public void restoreNetwork(UUID frequency, CompoundTag tag) {
        removeNetwork(frequency);
        if (tag.getBoolean("Present")) {
            NetworkRecord record = network(frequency);
            record.core = readGlobalPos(tag.getCompound(TAG_CORE));
            record.owner = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
            record.name = tag.getString(TAG_NAME);
            for (Tag value : tag.getList(TAG_MEMBERS, Tag.TAG_COMPOUND)) addMember(frequency, readMemberKey((CompoundTag) value));
            if (tag.getBoolean("LegacyFavorite")) legacyFavoriteNetwork = frequency;
        }
        memberRevision++;
        setDirty();
        WirelessAeNetworkRuntime.disconnectFrequency(frequency);
        WirelessAeNetworkRuntime.requestReconnect(frequency);
    }

    public GlobalPos getCore(UUID frequency) {
        NetworkRecord record = this.networks.get(frequency);
        return record == null ? null : record.core;
    }

    public UUID getNetworkOwner(UUID frequency) {
        NetworkRecord record = this.networks.get(frequency);
        return record == null ? null : record.owner;
    }

    public void setNetworkOwner(UUID frequency, UUID owner) {
        NetworkRecord record = network(frequency);
        if (!java.util.Objects.equals(record.owner, owner)) {
            record.owner = owner;
            setDirty();
        }
    }

    public void setNetworkName(UUID frequency, String name) {
        NetworkRecord record = network(frequency);
        String sanitized = sanitizeName(name, frequency);
        if (!sanitized.equals(record.name)) {
            record.name = sanitized;
            setDirty();
        }
    }

    public String getNetworkName(UUID frequency) {
        NetworkRecord record = this.networks.get(frequency);
        return record == null ? defaultName(frequency) : displayName(frequency, record.name);
    }

    public List<NetworkInfo> getNetworkInfo() {
        List<NetworkInfo> networks = new ArrayList<>();
        for (Map.Entry<UUID, NetworkRecord> entry : this.networks.entrySet()) {
            NetworkRecord record = entry.getValue();
            if (record.core != null) {
                networks.add(new NetworkInfo(entry.getKey(), displayName(entry.getKey(), record.name), record.core));
            }
        }
        networks.sort(Comparator.comparing(NetworkInfo::name, String.CASE_INSENSITIVE_ORDER));
        return networks;
    }

    public List<NetworkInfo> getNetworkInfo(MinecraftServer server) {
        pruneMissingCoreNetworks(server);
        return getNetworkInfo();
    }

    public UUID getFavoriteNetwork(ServerPlayer player) {
        UUID favoriteNetwork = readPlayerFavoriteNetwork(player);
        if (favoriteNetwork != null) {
            if (this.networks.containsKey(favoriteNetwork)) {
                return favoriteNetwork;
            }
            writePlayerFavoriteNetwork(player, null);
        }
        return migrateLegacyFavoriteNetwork(player);
    }

    public boolean setFavoriteNetwork(ServerPlayer player, UUID frequency) {
        UUID updated = frequency != null && this.networks.containsKey(frequency) ? frequency : null;
        if (java.util.Objects.equals(getFavoriteNetwork(player), updated)) {
            return false;
        }
        markLegacyFavoriteMigrated(player);
        writePlayerFavoriteNetwork(player, updated);
        return true;
    }

    public UUID getMemberNetwork(GlobalPos member) {
        return getMemberNetwork(new MemberKey(member, null));
    }

    public UUID getMemberNetwork(MemberKey member) {
        UUID exact = getExactMemberNetwork(member);
        if (exact != null) return exact;
        if (member.side() != null) {
            return getExactMemberNetwork(new MemberKey(member.pos(), null));
        }
        // Position-only lookup preserves the legacy fallback, with deterministic conflict resolution.
        UUID result = null;
        for (Map.Entry<UUID, NetworkRecord> entry : this.networks.entrySet()) {
            for (MemberKey candidate : entry.getValue().members) {
                if (candidate.pos().equals(member.pos()) &&
                        (result == null || entry.getKey().compareTo(result) < 0)) {
                    result = entry.getKey();
                }
            }
        }
        return result;
    }

    public UUID getExactMemberNetwork(MemberKey member) {
        UUID indexed = this.memberNetworks.get(member);
        if (indexed != null && this.networks.containsKey(indexed) && this.networks.get(indexed).members.contains(member)) {
            return indexed;
        }
        this.memberNetworks.remove(member);
        for (Map.Entry<UUID, NetworkRecord> entry : this.networks.entrySet()) {
            if (entry.getValue().members.contains(member)) {
                this.memberNetworks.put(member, entry.getKey());
                return entry.getKey();
            }
        }
        return null;
    }

    public Collection<UUID> getFrequencies() {
        return new HashSet<>(this.networks.keySet());
    }

    public Set<MemberKey> getMembers(UUID frequency) {
        NetworkRecord record = this.networks.get(frequency);
        if (record == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(record.members);
    }

    public int getMemberCount(UUID frequency) {
        NetworkRecord record = this.networks.get(frequency);
        return record == null ? 0 : record.members.size();
    }

    /** Server-thread, zero-copy cursor. Validate before resuming after a tick boundary. */
    MemberScan openMemberScan(UUID frequency) {
        NetworkRecord record = this.networks.get(frequency);
        return record == null ? null : new MemberScan(record);
    }

    final class MemberScan {

        private final NetworkRecord record;
        private final long revision;
        private final Iterator<MemberKey> iterator;
        private int remaining;

        private MemberScan(NetworkRecord record) {
            this.record = record;
            this.revision = record.memberRevision;
            this.iterator = record.members.iterator();
            this.remaining = record.members.size();
        }

        boolean isCurrent(UUID frequency) {
            return networks.get(frequency) == record && record.memberRevision == revision;
        }

        MemberKey next() {
            MemberKey member = iterator.next();
            remaining--;
            return member;
        }

        int remaining() {
            return remaining;
        }

        long revision() {
            return revision;
        }
    }

    public long getMemberRevision() {
        return memberRevision;
    }

    /** Membership revision scoped to one network; unrelated networks do not trigger a rescan. */
    public long getMemberRevision(UUID frequency) {
        NetworkRecord record = this.networks.get(frequency);
        return record == null ? 0L : record.memberRevision;
    }

    /** Exact membership check, without copying or applying side fallback rules. */
    public boolean containsMember(UUID frequency, MemberKey member) {
        NetworkRecord record = this.networks.get(frequency);
        return record != null && record.members.contains(member);
    }

    public boolean addMember(UUID frequency, GlobalPos member) {
        return addMember(frequency, new MemberKey(member, null));
    }

    public boolean addMember(UUID frequency, MemberKey member) {
        NetworkRecord record = network(frequency);
        UUID previous = getExactMemberNetwork(member);
        if (previous != null && !previous.equals(frequency)) {
            NetworkRecord old = this.networks.get(previous);
            if (old != null) {
                if (old.members.remove(member)) old.memberRevision++;
            }
        }
        boolean added = record.members.add(member);
        this.memberNetworks.put(member, frequency);
        if (added || previous != null && !previous.equals(frequency)) {
            record.memberRevision++;
            memberRevision++;
            setDirty();
        }
        return added;
    }

    public boolean removeMember(UUID frequency, GlobalPos member) {
        return removeMember(frequency, new MemberKey(member, null));
    }

    public boolean removeMember(UUID frequency, MemberKey member) {
        NetworkRecord record = this.networks.get(frequency);
        if (record == null) {
            return false;
        }
        boolean removed = record.members.remove(member);
        if (removed) {
            this.memberNetworks.remove(member, frequency);
        }
        if (removed) {
            record.memberRevision++;
            memberRevision++;
            setDirty();
        }
        return removed;
    }

    public Set<UUID> removeMember(GlobalPos member) {
        return removeMembersAt(member);
    }

    public Set<UUID> removeMember(MemberKey member) {
        Set<UUID> removedFrom = new HashSet<>();
        for (Map.Entry<UUID, NetworkRecord> entry : this.networks.entrySet()) {
            if (entry.getValue().members.remove(member)) {
                removedFrom.add(entry.getKey());
                this.memberNetworks.remove(member, entry.getKey());
            }
        }
        if (!removedFrom.isEmpty()) {
            for (UUID frequency : removedFrom) {
                NetworkRecord record = this.networks.get(frequency);
                if (record != null) record.memberRevision++;
            }
            memberRevision++;
            setDirty();
        }
        return removedFrom;
    }

    public Set<UUID> removeMembersAt(GlobalPos member) {
        this.memberNetworks.keySet().removeIf(key -> key.pos().equals(member));
        Set<UUID> removedFrom = new HashSet<>();
        for (Map.Entry<UUID, NetworkRecord> entry : this.networks.entrySet()) {
            boolean removed = entry.getValue().members.removeIf(candidate -> candidate.pos().equals(member));
            if (removed) {
                removedFrom.add(entry.getKey());
            }
        }
        if (!removedFrom.isEmpty()) {
            for (UUID frequency : removedFrom) {
                NetworkRecord record = this.networks.get(frequency);
                if (record != null) record.memberRevision++;
            }
            memberRevision++;
            setDirty();
        }
        return removedFrom;
    }

    public Set<UUID> removeNetworksAtCore(GlobalPos core) {
        Set<UUID> removed = new HashSet<>();
        this.networks.entrySet().removeIf(entry -> {
            if (core.equals(entry.getValue().core)) {
                removed.add(entry.getKey());
                return true;
            }
            return false;
        });
        if (!removed.isEmpty()) {
            this.memberNetworks.entrySet().removeIf(entry -> removed.contains(entry.getValue()));
            if (removed.contains(this.legacyFavoriteNetwork)) {
                this.legacyFavoriteNetwork = null;
            }
            setDirty();
        }
        return removed;
    }

    public void removeNetwork(UUID frequency) {
        this.memberNetworks.entrySet().removeIf(entry -> entry.getValue().equals(frequency));
        if (this.networks.remove(frequency) != null) {
            if (frequency.equals(this.legacyFavoriteNetwork)) {
                this.legacyFavoriteNetwork = null;
            }
            setDirty();
        }
    }

    private void pruneMissingCoreNetworks(MinecraftServer server) {
        Set<UUID> removed = new HashSet<>();
        this.networks.entrySet().removeIf(entry -> {
            GlobalPos core = entry.getValue().core;
            if (core == null) {
                removed.add(entry.getKey());
                return true;
            }

            ServerLevel level = server.getLevel(core.dimension());
            if (level == null || !level.hasChunkAt(core.pos())) {
                return false;
            }
            if (!level.getBlockState(core.pos()).is(GTLWirelessAeContent.WIRELESS_NETWORK_CORE.get())) {
                removed.add(entry.getKey());
                return true;
            }
            return false;
        });
        this.memberNetworks.entrySet().removeIf(entry -> removed.contains(entry.getValue()));
        if (!removed.isEmpty()) {
            if (removed.contains(this.legacyFavoriteNetwork)) {
                this.legacyFavoriteNetwork = null;
            }
            setDirty();
        }
    }

    private UUID migrateLegacyFavoriteNetwork(ServerPlayer player) {
        if (this.legacyFavoriteNetwork == null || hasMigratedLegacyFavorite(player)) {
            return null;
        }
        if (!WirelessAeNetworkRuntime.canAccessNetwork(player, this.legacyFavoriteNetwork)) {
            return null;
        }

        UUID migrated = this.legacyFavoriteNetwork;
        writePlayerFavoriteNetwork(player, migrated);
        markLegacyFavoriteMigrated(player);
        return migrated;
    }

    private static boolean hasMigratedLegacyFavorite(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.getCompound(TAG_PLAYER_DATA).getBoolean(TAG_LEGACY_FAVORITE_MIGRATED);
    }

    private static void markLegacyFavoriteMigrated(ServerPlayer player) {
        CompoundTag entityData = player.getPersistentData();
        CompoundTag persisted = entityData.getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag wirelessData = persisted.getCompound(TAG_PLAYER_DATA);
        wirelessData.putBoolean(TAG_LEGACY_FAVORITE_MIGRATED, true);
        persisted.put(TAG_PLAYER_DATA, wirelessData);
        entityData.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private static UUID readPlayerFavoriteNetwork(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag wirelessData = persisted.getCompound(TAG_PLAYER_DATA);
        return wirelessData.hasUUID(TAG_FAVORITE_NETWORK) ? wirelessData.getUUID(TAG_FAVORITE_NETWORK) : null;
    }

    private static void writePlayerFavoriteNetwork(ServerPlayer player, UUID frequency) {
        CompoundTag entityData = player.getPersistentData();
        CompoundTag persisted = entityData.getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag wirelessData = persisted.getCompound(TAG_PLAYER_DATA);
        if (frequency == null) {
            wirelessData.remove(TAG_FAVORITE_NETWORK);
        } else {
            wirelessData.putUUID(TAG_FAVORITE_NETWORK, frequency);
        }
        persisted.put(TAG_PLAYER_DATA, wirelessData);
        entityData.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private NetworkRecord network(UUID frequency) {
        return this.networks.computeIfAbsent(frequency, ignored -> new NetworkRecord());
    }

    private static String sanitizeName(String name, UUID frequency) {
        String sanitized = name == null ? "" : name.trim();
        if (sanitized.isEmpty()) {
            return defaultName(frequency);
        }
        return sanitized.length() > 32 ? sanitized.substring(0, 32) : sanitized;
    }

    private static String displayName(UUID frequency, String name) {
        return sanitizeName(name, frequency);
    }

    private static String defaultName(UUID frequency) {
        return "Wireless " + frequency.toString().substring(0, 8);
    }

    private static CompoundTag writeGlobalPos(GlobalPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_DIMENSION, pos.dimension().location().toString());
        tag.putInt(TAG_X, pos.pos().getX());
        tag.putInt(TAG_Y, pos.pos().getY());
        tag.putInt(TAG_Z, pos.pos().getZ());
        return tag;
    }

    private static GlobalPos readGlobalPos(CompoundTag tag) {
        if (!tag.contains(TAG_DIMENSION)) {
            return null;
        }

        try {
            ResourceKey<Level> dimension = ResourceKey.create(
                    Registries.DIMENSION,
                    new ResourceLocation(tag.getString(TAG_DIMENSION)));
            BlockPos pos = new BlockPos(tag.getInt(TAG_X), tag.getInt(TAG_Y), tag.getInt(TAG_Z));
            return GlobalPos.of(dimension, pos);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static CompoundTag writeMemberKey(MemberKey member) {
        CompoundTag tag = writeGlobalPos(member.pos());
        if (member.side() != null) {
            tag.putString(TAG_SIDE, member.side().getName());
        }
        return tag;
    }

    private static MemberKey readMemberKey(CompoundTag tag) {
        GlobalPos pos = readGlobalPos(tag);
        if (pos == null) {
            return null;
        }

        Direction side = null;
        if (tag.contains(TAG_SIDE)) {
            side = Direction.byName(tag.getString(TAG_SIDE));
            // An invalid face must not silently become a whole-block binding.
            if (side == null) return null;
        }
        return new MemberKey(pos, side);
    }

    private static final class NetworkRecord {

        private GlobalPos core;
        private UUID owner;
        private String name = "";
        private long memberRevision;
        // Linked iteration starts/resumes in O(1), including after a large network shrinks.
        private final Set<MemberKey> members = new LinkedHashSet<>();
    }

    public record NetworkInfo(UUID frequency, String name, GlobalPos core) {}

    public record MemberKey(GlobalPos pos, Direction side) {

        public static MemberKey of(ResourceKey<Level> dimension, BlockPos pos, Direction side) {
            return new MemberKey(GlobalPos.of(dimension, pos), side);
        }

        public BlockPos blockPos() {
            return this.pos.pos();
        }
    }
}
