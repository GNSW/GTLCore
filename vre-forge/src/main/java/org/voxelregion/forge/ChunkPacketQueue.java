/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge;

import io.netty.buffer.Unpooled;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** One current chunk image after light completion; bandwidth includes each tracking recipient. */
public final class ChunkPacketQueue {
    private static final class Request {
        final ServerLevel level;
        final LevelChunk chunk;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<Void> light;
        boolean waitingPrerequisite;
        long epoch;
        Request(ServerLevel level, LevelChunk chunk, CompletableFuture<Void> prerequisite) {
            this.level = level; this.chunk = chunk;
            if (prerequisite.isDone()) { prerequisite.join(); awaitCurrentLight(); }
            else { light = prerequisite; waitingPrerequisite = true; }
        }
        void awaitCurrentLight() {
            epoch = NativeCommitFences.revision(chunk);
            light = NativeCommitFences.light(level, chunk);
        }
    }
    private static final ArrayDeque<Request> pending = new ArrayDeque<>();
    private static long debt, sentBytes;
    public static CompletableFuture<Void> enqueue(ServerLevel level, LevelChunk chunk) {
        return enqueue(level, chunk, CompletableFuture.completedFuture(null));
    }
    /** Publish only after custom light data and its native boundary reconciliation are complete. */
    public static CompletableFuture<Void> enqueue(ServerLevel level, LevelChunk chunk, CompletableFuture<Void> prerequisite) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Packet queue requires server thread");
        if (pending.size() >= 4096) throw new IllegalStateException("Chunk packet queue capacity reached");
        var request = new Request(level, chunk, prerequisite);
        pending.add(request);
        return request.result;
    }
    public static void tick(MinecraftServer server, long bytesPerTick) {
        tick(server, bytesPerTick, System.nanoTime() + 1_000_000);
    }
    public static void tick(MinecraftServer server, long bytesPerTick, long deadline) {
        if (!server.isSameThread()) throw new IllegalStateException("Packet queue requires server thread");
        long remaining = Math.max(0, bytesPerTick - debt);
        debt = Math.max(0, debt - bytesPerTick);
        var iterator = pending.iterator();
        while (remaining > 0 && iterator.hasNext() && System.nanoTime() < deadline) {
            Request r = iterator.next();
            if (!r.light.isDone()) continue;
            try {
                r.light.join();
                if (r.waitingPrerequisite) {
                    r.waitingPrerequisite = false;
                    r.awaitCurrentLight();
                    continue;
                }
                if (r.level.getChunkSource().getChunkNow(r.chunk.getPos().x, r.chunk.getPos().z) != r.chunk)
                    throw new IllegalStateException("Chunk ticket was released before its packet fence");
                if (NativeCommitFences.revision(r.chunk) != r.epoch) {
                    r.awaitCurrentLight();
                    continue;
                }
                var players = r.level.getChunkSource().chunkMap.getPlayers(r.chunk.getPos(), false);
                if (!players.isEmpty()) {
                    var packet = new ClientboundLevelChunkWithLightPacket(r.chunk, r.level.getChunkSource().getLightEngine(), null, null);
                    CompleteChunkLightPacket.fillMissing(packet.getLightData(), r.level.getChunkSource().getLightEngine(),
                            r.chunk.getPos(), r.level.dimensionType().hasSkyLight());
                    var buffer = new FriendlyByteBuf(Unpooled.buffer());
                    long bytes;
                    try { packet.write(buffer); bytes = (long) buffer.readableBytes() * players.size(); }
                    finally { buffer.release(); }
                    if (NativeCommitFences.revision(r.chunk) != r.epoch)
                        throw new IllegalStateException("Chunk changed while encoding its packet; stable terrain/light image cannot be submitted");
                    // A native packet is indivisible. An oversized packet incurs debt; it cannot
                    // permanently starve and no further packets are emitted until debt is repaid.
                    for (var player : players) player.connection.send(packet);
                    sentBytes += bytes;
                    if (bytes > remaining) { debt += bytes - remaining; remaining = 0; }
                    else remaining -= bytes;
                }
                r.result.complete(null); // Submitted to native connections, not a remote client acknowledgement.
            } catch (RuntimeException | LinkageError ex) {
                r.result.completeExceptionally(ex);
            }
            iterator.remove();
        }
    }
    public static long sentBytes() { return sentBytes; }
    public static void stop() {
        for (var r : pending) r.result.completeExceptionally(new IllegalStateException("Server stopped before packet submission"));
        pending.clear(); debt = 0; sentBytes = 0;
    }
    private ChunkPacketQueue() {}
}
