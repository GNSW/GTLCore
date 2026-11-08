package org.gtlcore.gtlcore.mixin.gtmt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.LevelEvent;

import com.hepdd.gtmthings.api.misc.GlobalVariableStorage;
import com.hepdd.gtmthings.data.WirelessEnergySavaedData;
import com.hepdd.gtmthings.forge.ForgeCommonEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Mixin(value = ForgeCommonEventListener.class, remap = false)
public abstract class ForgeCommonEventListenerMixin {

    private static final String ALL_ENERGY = "allEnergy";

    @Inject(method = "serverSetup", at = @At("HEAD"), cancellable = true)
    private static void gtlcore$loadOnlyOverworld(LevelEvent.Load event, CallbackInfo ci) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) ci.cancel();
    }

    @Inject(method = "serverSetup", at = @At("RETURN"))
    private static void gtlcore$migrateOldDimensionFiles(LevelEvent.Load event, CallbackInfo ci) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) return;
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Map<UUID, BigInteger> merged = new HashMap<>();
        int[] found = { 0 };
        int[] read = { 0 };
        if (Files.isDirectory(root)) {
            try (var files = Files.walk(root, 6)) {
                files.filter(ForgeCommonEventListenerMixin::isWirelessEnergyFile)
                        .forEach(path -> {
                            found[0]++;
                            if (mergeFile(path, merged)) read[0]++;
                        });
            } catch (Exception ignored) {}
        }
        boolean replaced = read[0] > 0 || found[0] == 0;
        if (replaced) {
            GlobalVariableStorage.GlobalEnergy.clear();
            GlobalVariableStorage.GlobalEnergy.putAll(merged);
            if (WirelessEnergySavaedData.INSTANCE != null) WirelessEnergySavaedData.INSTANCE.setDirty();
        }
    }

    private static boolean isWirelessEnergyFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".dat") && (name.contains("wireless") || name.contains("energy"));
    }

    private static boolean mergeFile(Path path, Map<UUID, BigInteger> merged) {
        try {
            CompoundTag root = NbtIo.readCompressed(path.toFile());
            CompoundTag data = findEnergyData(root);
            if (data == null) return false;
            ListTag list = data.getList(ALL_ENERGY, Tag.TAG_COMPOUND);
            for (Tag element : list) {
                CompoundTag entry = (CompoundTag) element;
                UUID uuid = entry.getUUID("uuid");
                if (uuid == null) continue;
                String energy = entry.getString("energy");
                merged.merge(uuid, new BigInteger(energy.isEmpty() ? "0" : energy), BigInteger::max);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static CompoundTag findEnergyData(CompoundTag root) {
        if (root.contains(ALL_ENERGY, Tag.TAG_LIST)) return root;
        for (String key : root.getAllKeys()) {
            if (root.get(key) instanceof CompoundTag child && child.contains(ALL_ENERGY, Tag.TAG_LIST)) return child;
        }
        return null;
    }
}
