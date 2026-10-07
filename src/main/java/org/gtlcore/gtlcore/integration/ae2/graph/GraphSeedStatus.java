package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import org.cgse.core.GraphPlan;

import java.util.ArrayList;
import java.util.List;

/** Display proof scope without presenting an incumbent as a certified optimum. */
public final class GraphSeedStatus {

    private GraphSeedStatus() {}

    public static void write(FriendlyByteBuf buffer, GraphPlan.SeedOptimality proof) {
        buffer.writeBoolean(proof != null);
        if (proof == null) return;
        buffer.writeVarInt(proof.lowerTypeBound());
        buffer.writeVarInt(proof.types());
        buffer.writeBoolean(proof.cardinalityProven());
        buffer.writeBoolean(proof.quantitiesParetoProven());
        buffer.writeByte((proof.fundedPreview() ? 1 : 0) | (proof.baseMaterialTradeoff() ? 2 : 0));
    }

    public static GraphPlan.SeedOptimality read(FriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return null;
        int lower = buffer.readVarInt(), types = buffer.readVarInt();
        if (types < 0 || types > 100_000) throw new IllegalArgumentException("Invalid seed type count");
        boolean cardinality = buffer.readBoolean(), quantities = buffer.readBoolean();
        int scope = buffer.readUnsignedByte();
        if (scope > 3) throw new IllegalArgumentException("Invalid seed proof scope");
        return new GraphPlan.SeedOptimality(lower, types, cardinality, quantities, (scope & 1) != 0, (scope & 2) != 0);
    }

    public static List<Component> tooltip(GraphPlan.SeedOptimality proof) {
        if (proof == null) return List.of(Component.translatable("gtlcore.ae.graph.seed_unproven"));
        List<Component> lines = new ArrayList<>();
        lines.add(proof.cardinalityProven() ? Component.translatable("gtlcore.ae.graph.seed_types_proven", proof.types()) :
                proof.lowerTypeBound() > 0 ? Component.translatable("gtlcore.ae.graph.seed_types_bound", proof.types(), proof.lowerTypeBound()) :
                        Component.translatable("gtlcore.ae.graph.seed_types_unproven", proof.types()));
        lines.add(Component.translatable(proof.quantitiesParetoProven() ? "gtlcore.ae.graph.seed_quantities_proven" : "gtlcore.ae.graph.seed_quantities_unproven"));
        lines.add(Component.translatable(proof.baseMaterialTradeoff() ? "gtlcore.ae.graph.seed_scope_material_tradeoff" :
                proof.fundedPreview() ? "gtlcore.ae.graph.seed_scope_refill" : "gtlcore.ae.graph.seed_scope_stock"));
        return lines;
    }
}
