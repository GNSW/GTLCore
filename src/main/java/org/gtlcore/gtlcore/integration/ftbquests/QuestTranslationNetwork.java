package org.gtlcore.gtlcore.integration.ftbquests;

import org.gtlcore.gtlcore.GTLCore;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Separate server-authoritative channel; editing a translation never rewrites a quest or its progress. */
public final class QuestTranslationNetwork {

    private static final String VERSION = "1";
    private static final int CHUNK_LENGTH = 16000;
    private static final int MAX_SNAPSHOT_LENGTH = QuestTranslationCatalog.MAX_FILE_BYTES * 2 + 65536;
    private static final Gson JSON = new Gson();
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GTLCore.MOD_ID, "quest_translations"), () -> VERSION,
            version -> VERSION.equals(version) || NetworkRegistry.ABSENT.equals(version),
            version -> VERSION.equals(version) || NetworkRegistry.ABSENT.equals(version));
    private static final Map<UUID, RequestBudget> REQUESTS = new HashMap<>();
    private static final Map<UUID, ServerPlayer> SUBSCRIBERS = new HashMap<>();
    private static MinecraftServer owner;
    private static Object questFile;
    private static QuestTranslationCatalog catalog;
    private static String loadError;
    private static int transferSequence;
    private static ClientReceiver client;
    private static int receivingSequence = -1;
    private static int receivingCount;
    private static int receivedParts;
    private static StringBuilder receiving;

    private QuestTranslationNetwork() {}

    public interface ClientReceiver {

        void acceptSnapshot(QuestTranslationCatalog.Snapshot snapshot);

        void edited(String locale, String key, String value, boolean success, String message);

        void invalidated();
    }

    public static void setClientReceiver(ClientReceiver receiver) {
        client = receiver;
    }

    public static void clearClientTransfer() {
        receiving = null;
        receivingSequence = -1;
    }

    public static void register() {
        register(0, Request.class, (p, b) -> b.writeUtf(p.locale, 32), b -> new Request(b.readUtf(32)),
                QuestTranslationNetwork::requestOnServer, NetworkDirection.PLAY_TO_SERVER);
        register(1, Edit.class, Edit::encode, Edit::decode, QuestTranslationNetwork::editOnServer, NetworkDirection.PLAY_TO_SERVER);
        register(2, Reload.class, (p, b) -> {}, b -> new Reload(), (p, c) -> reloadOnServer(c), NetworkDirection.PLAY_TO_SERVER);
        register(3, Part.class, Part::encode, Part::decode, (p, c) -> receivePart(p), NetworkDirection.PLAY_TO_CLIENT);
        register(4, Result.class, Result::encode, Result::decode, (p, c) -> {
            if (client != null) client.edited(p.locale, p.key, p.value, p.success, p.message);
        }, NetworkDirection.PLAY_TO_CLIENT);
        register(5, Invalidate.class, (p, b) -> {}, b -> new Invalidate(), (p, c) -> {
            clearClientTransfer();
            if (client != null) client.invalidated();
        }, NetworkDirection.PLAY_TO_CLIENT);
        register(6, Enroll.class, (p, b) -> {}, b -> new Enroll(), (p, c) -> enrollOnServer(c), NetworkDirection.PLAY_TO_SERVER);
        MinecraftForge.EVENT_BUS.addListener(QuestTranslationNetwork::stopped);
        MinecraftForge.EVENT_BUS.addListener(QuestTranslationNetwork::loggedOut);
    }

    private static <T> void register(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> encode,
                                     Function<FriendlyByteBuf, T> decode, BiConsumer<T, NetworkEvent.Context> handle,
                                     NetworkDirection direction) {
        CHANNEL.registerMessage(id, type, encode, decode, (packet, supplier) -> {
            var context = supplier.get();
            context.enqueueWork(() -> handle.accept(packet, context));
            context.setPacketHandled(true);
        }, java.util.Optional.of(direction));
    }

    public static void request(String locale) {
        CHANNEL.sendToServer(new Request(locale));
    }

    public static boolean isAvailable(Connection connection) {
        return connection != null && CHANNEL.isRemotePresent(connection);
    }

    public static void edit(String locale, String key, String expected, String replacement) {
        CHANNEL.sendToServer(new Edit(locale, key, expected, replacement));
    }

    public static void reload() {
        CHANNEL.sendToServer(new Reload());
    }

    public static void enroll() {
        CHANNEL.sendToServer(new Enroll());
    }

    private static Object serverFile() {
        try {
            return Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile").getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static QuestTranslationCatalog getCatalog(ServerPlayer player) {
        Object current = serverFile();
        if (owner != player.server || questFile != current) {
            owner = player.server;
            questFile = current;
            catalog = null;
            loadError = null;
            if (current != null) {
                try {
                    catalog = load(current);
                } catch (IOException e) {
                    loadError = e.getMessage();
                    GTLCore.LOGGER.error("Could not load quest translations", e);
                }
            }
        }
        return catalog;
    }

    private static QuestTranslationCatalog load(Object file) throws IOException {
        try {
            Path folder = (Path) file.getClass().getMethod("getFolder").invoke(file);
            return folder == null ? null : QuestTranslationCatalog.load(folder);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Cannot access the FTB Quests directory", e);
        }
    }

    private static boolean canEdit(ServerPlayer player) {
        Object file = serverFile();
        if (file == null) return false;
        try {
            Object team = file.getClass().getMethod("getOrCreateTeamData", Entity.class).invoke(file, player);
            return Boolean.TRUE.equals(team.getClass().getMethod("getCanEdit", Player.class).invoke(team, player));
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean budget(ServerPlayer player) {
        if (player == null) return false;
        int tick = player.server.getTickCount();
        RequestBudget budget = REQUESTS.computeIfAbsent(player.getUUID(), ignored -> new RequestBudget());
        if (tick - budget.tick >= 20 || tick < budget.tick) {
            budget.tick = tick;
            budget.count = 0;
        }
        return ++budget.count <= 16;
    }

    private static void requestOnServer(Request packet, NetworkEvent.Context context) {
        ServerPlayer player = context.getSender();
        if (!budget(player)) return;
        try {
            QuestTranslationCatalog.validateLocale(packet.locale);
            QuestTranslationCatalog active = getCatalog(player);
            SUBSCRIBERS.put(player.getUUID(), player);
            var snapshot = active == null ? QuestTranslationCatalog.Snapshot.disabled(packet.locale, canEdit(player)) : active.snapshot(packet.locale, canEdit(player));
            String json = JSON.toJson(snapshot);
            if (json.length() > MAX_SNAPSHOT_LENGTH) throw new IOException("Quest translation snapshot too large");
            var chunks = new ArrayList<String>();
            for (int start = 0; start < json.length();) {
                int end = Math.min(json.length(), start + CHUNK_LENGTH);
                if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1)) && Character.isLowSurrogate(json.charAt(end))) end--;
                chunks.add(json.substring(start, end));
                start = end;
            }
            int count = chunks.size();
            int sequence = ++transferSequence;
            for (int index = 0; index < count; index++) {
                send(player, new Part(sequence, index, count, chunks.get(index)));
            }
            if (loadError != null && canEdit(player)) send(player, new Result(packet.locale, "", null, false, loadError));
        } catch (IOException e) {
            send(player, new Result(packet.locale, "", null, false, e.getMessage()));
        }
    }

    private static void editOnServer(Edit packet, NetworkEvent.Context context) {
        ServerPlayer player = context.getSender();
        if (!budget(player)) return;
        try {
            if (!canEdit(player)) throw new IOException("FTB Quests editing permission is required");
            QuestTranslationCatalog active = getCatalog(player);
            if (active == null) throw new IOException("This quest book has no translation catalog");
            active.edit(packet.locale, packet.key, packet.expected, packet.replacement);
            Result result = new Result(packet.locale, packet.key, packet.replacement, true, "");
            SUBSCRIBERS.put(player.getUUID(), player);
            for (ServerPlayer subscriber : SUBSCRIBERS.values()) send(subscriber, result);
        } catch (IOException e) {
            send(player, new Result(packet.locale, packet.key, null, false, e.getMessage()));
        }
    }

    private static void reloadOnServer(NetworkEvent.Context context) {
        ServerPlayer player = context.getSender();
        if (!budget(player)) return;
        try {
            if (!canEdit(player)) throw new IOException("FTB Quests editing permission is required");
            getCatalog(player);
            if (questFile == null) throw new IOException("FTB Quests is unavailable");
            // Load completely before publishing; malformed imports retain the previous good catalog.
            QuestTranslationCatalog loaded = load(questFile);
            catalog = loaded;
            loadError = null;
            for (ServerPlayer subscriber : SUBSCRIBERS.values()) send(subscriber, new Invalidate());
        } catch (IOException e) {
            send(player, new Result("", "", null, false, e.getMessage()));
        }
    }

    private static void enrollOnServer(NetworkEvent.Context context) {
        ServerPlayer player = context.getSender();
        if (!budget(player)) return;
        try {
            if (!canEdit(player)) throw new IOException("FTB Quests editing permission is required");
            QuestTranslationCatalog active = getCatalog(player);
            if (active == null) throw new IOException("Convert this quest book before enrolling new text");
            int added = QuestTranslationEnrollment.enroll(questFile, active, player.server);
            SUBSCRIBERS.put(player.getUUID(), player);
            for (ServerPlayer subscriber : SUBSCRIBERS.values()) send(subscriber, new Invalidate());
            send(player, new Result("", "", null, true, "Enrolled " + added + " quest translation keys"));
        } catch (IOException e) {
            send(player, new Result("", "", null, false, e.getMessage()));
        }
    }

    private static void receivePart(Part part) {
        if (part.count <= 0 || part.count > (MAX_SNAPSHOT_LENGTH / (CHUNK_LENGTH - 1)) + 1 || part.index < 0 || part.index >= part.count) return;
        if (part.index == 0) {
            receiving = new StringBuilder();
            receivingSequence = part.sequence;
            receivingCount = part.count;
            receivedParts = 0;
        }
        if (receiving == null || part.sequence != receivingSequence || part.count != receivingCount || part.index != receivedParts) return;
        if (receiving.length() + part.text.length() > MAX_SNAPSHOT_LENGTH) {
            clearClientTransfer();
            return;
        }
        receiving.append(part.text);
        if (++receivedParts == receivingCount) {
            String text = receiving.toString();
            clearClientTransfer();
            try {
                QuestTranslationCatalog.Snapshot snapshot = JSON.fromJson(text, QuestTranslationCatalog.Snapshot.class);
                if (client != null && snapshot != null) client.acceptSnapshot(snapshot);
            } catch (RuntimeException e) {
                GTLCore.LOGGER.warn("Invalid quest translation snapshot", e);
            }
        }
    }

    private static void send(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static void stopped(ServerStoppedEvent event) {
        owner = null;
        questFile = null;
        catalog = null;
        loadError = null;
        REQUESTS.clear();
        SUBSCRIBERS.clear();
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        REQUESTS.remove(event.getEntity().getUUID());
        SUBSCRIBERS.remove(event.getEntity().getUUID());
    }

    private static void writeOptional(FriendlyByteBuf buffer, String value) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeUtf(value, QuestTranslationCatalog.MAX_VALUE_LENGTH);
    }

    private static String readOptional(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? buffer.readUtf(QuestTranslationCatalog.MAX_VALUE_LENGTH) : null;
    }

    private static final class RequestBudget {

        private int tick;
        private int count;
    }

    private record Request(String locale) {}

    private record Reload() {}

    private record Enroll() {}

    private record Invalidate() {}

    private record Edit(String locale, String key, String expected, String replacement) {

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(locale, 32);
            buffer.writeUtf(key, QuestTranslationCatalog.MAX_KEY_LENGTH);
            writeOptional(buffer, expected);
            writeOptional(buffer, replacement);
        }

        static Edit decode(FriendlyByteBuf buffer) {
            return new Edit(buffer.readUtf(32), buffer.readUtf(QuestTranslationCatalog.MAX_KEY_LENGTH), readOptional(buffer), readOptional(buffer));
        }
    }

    private record Part(int sequence, int index, int count, String text) {

        void encode(FriendlyByteBuf buffer) {
            buffer.writeInt(sequence);
            buffer.writeVarInt(index);
            buffer.writeVarInt(count);
            buffer.writeUtf(text, CHUNK_LENGTH);
        }

        static Part decode(FriendlyByteBuf buffer) {
            return new Part(buffer.readInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(CHUNK_LENGTH));
        }
    }

    private record Result(String locale, String key, String value, boolean success, String message) {

        void encode(FriendlyByteBuf buffer) {
            buffer.writeUtf(locale, 32);
            buffer.writeUtf(key, QuestTranslationCatalog.MAX_KEY_LENGTH);
            writeOptional(buffer, value);
            buffer.writeBoolean(success);
            buffer.writeUtf(message, 2048);
        }

        static Result decode(FriendlyByteBuf buffer) {
            return new Result(buffer.readUtf(32), buffer.readUtf(QuestTranslationCatalog.MAX_KEY_LENGTH), readOptional(buffer), buffer.readBoolean(), buffer.readUtf(2048));
        }
    }
}
