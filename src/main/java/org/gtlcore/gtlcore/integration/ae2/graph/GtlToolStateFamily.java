package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import org.cgse.core.StateFamily;

import java.util.Objects;

/** Immutable, captured GT tool arithmetic. No ItemStack, recipe, capability or world callbacks. */
final class GtlToolStateFamily implements StateFamily<AEKey> {

    private final String id;
    private final CompoundTag signature;
    private final int maximum, damage, wearChance;
    private final long chargeMaximum, chargeCost;
    private final boolean unbreakable, electric, discharge;
    private final AEKey broken;

    GtlToolStateFamily(AEItemKey initial, String semantics, int maximum, int damage, boolean unbreakable,
                       boolean electric, long chargeMaximum, long chargeCost, boolean discharge, int wearChance, AEKey broken) {
        this.maximum = maximum;
        this.damage = damage;
        this.unbreakable = unbreakable;
        this.electric = electric;
        this.chargeMaximum = chargeMaximum;
        this.chargeCost = chargeCost;
        this.discharge = discharge;
        this.wearChance = wearChance;
        this.broken = broken;
        if (maximum < 0 || damage < 0 || maximum > Integer.MAX_VALUE - damage || chargeMaximum < 0 || chargeCost < 0)
            throw new IllegalArgumentException("Invalid captured tool arithmetic");
        signature = normalized(initial);
        if (signature == null) throw new IllegalArgumentException("Unsupported captured tool NBT");
        id = "gt-tool-capacity/v1/" + semantics + "/max=" + maximum + "/damage=" + damage + "/unbreakable=" + unbreakable +
                "/electric=" + electric + "/chargeMax=" + chargeMaximum + "/chargeCost=" + chargeCost + "/discharge=" + discharge +
                "/chance=" + wearChance + "/broken=" + (broken == null ? "empty" : PatternFingerprint.key(broken)) +
                "/family=" + PatternFingerprint.key(Objects.requireNonNull(AEItemKey.fromTag(signature)));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean accepts(AEKey state) {
        return state instanceof AEItemKey item && signature.equals(normalized(item));
    }

    @Override
    public long capacity(AEKey state) {
        if (!accepts(state)) return 0;
        if (unbreakable || damage == 0) return Long.MAX_VALUE;
        CompoundTag tag = ((AEItemKey) state).toTag().getCompound("tag");
        int before = tag.getCompound("GT.Tool").getInt("Damage");
        // A physically present over-worn item is consumed on its next legal
        // use. It must not acquire a negative or wrapped capacity.
        long uses = before > maximum ? 1 : ((long) maximum - before) / damage + 1;
        if (!electric || wearChance > 0) return uses;
        long charged = charge(tag);
        if (charged <= 0) return uses;
        if (!discharge || chargeCost == 0 || tag.getBoolean("Infinite")) return Long.MAX_VALUE;
        long poweredUses = (charged - 1) / chargeCost;
        return poweredUses > Long.MAX_VALUE - uses ? Long.MAX_VALUE : poweredUses + uses;
    }

    @Override
    public AEKey nominalAfterOne(AEKey state) {
        if (!accepts(state)) throw new IllegalArgumentException("Tool is outside captured family");
        if (unbreakable) return state;
        CompoundTag payload = ((AEItemKey) state).toTag();
        CompoundTag tag = payload.getCompound("tag");
        boolean changed = false;
        if (electric && discharge && chargeCost > 0) {
            long before = charge(tag);
            tag.putLong("Charge", before - Math.min(chargeCost, before));
            changed = true;
        }
        int wear = electric && charge(tag) > 0 && wearChance <= 0 ? 0 : damage;
        if (wear > 0) {
            CompoundTag tool = tag.getCompound("GT.Tool");
            long after = (long) tool.getInt("Damage") + wear;
            if (after > Integer.MAX_VALUE) throw new IllegalArgumentException("Tool damage exceeds supported arithmetic");
            if (after > maximum) return broken;
            tool.putInt("Damage", (int) after);
            // Positive wear invokes GT's lazy max-damage getter; a zero-loss
            // return does not. Preserve that distinction in exact AE key NBT.
            tool.putInt("MaxDamage", maximum);
            tag.put("GT.Tool", tool);
            changed = true;
        }
        if (!changed) return state;
        payload.put("tag", tag);
        return Objects.requireNonNull(AEItemKey.fromTag(payload), "Captured tool key no longer registered");
    }

    private long charge(CompoundTag tag) {
        return tag.getBoolean("Infinite") ? chargeMaximum : Math.min(tag.getLong("Charge"), chargeMaximum);
    }

    private CompoundTag normalized(AEItemKey key) {
        CompoundTag payload = key.toTag();
        if (payload.contains("tag") && !payload.contains("tag", Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = payload.getCompound("tag");
        if (tag.contains("GT.Tool") && !tag.contains("GT.Tool", Tag.TAG_COMPOUND)) return null;
        CompoundTag tool = tag.getCompound("GT.Tool");
        if (tool.contains("Damage") && !tool.contains("Damage", Tag.TAG_INT)) return null;
        int before = tool.getInt("Damage");
        if (before < 0 || before > Integer.MAX_VALUE - damage) return null;
        if (tool.contains("MaxDamage") && (!tool.contains("MaxDamage", Tag.TAG_INT) || tool.getInt("MaxDamage") != maximum)) return null;
        tool.remove("Damage");
        tool.putInt("MaxDamage", maximum);
        tag.put("GT.Tool", tool);
        if (electric) {
            if (tag.contains("Charge") && (!tag.contains("Charge", Tag.TAG_LONG) || tag.getLong("Charge") < 0)) return null;
            tag.remove("Charge");
        }
        payload.put("tag", tag);
        return payload;
    }
}
