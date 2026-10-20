package org.gtlcore.gtlcore.mixin.ftbquests;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.InvocationTargetException;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbxmodcompat.ftbquests.recipemod_common.WrappedQuestCache", remap = false)
public abstract class QuestRewardLookupMixin {

    @Redirect(
              method = "lambda$rebuildWrappedQuestCache$1",
              at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbquests/quest/TeamData;canStartTasks(Ldev/ftb/mods/ftbquests/quest/Quest;)Z"),
              remap = false)
    private boolean gtlcore$showVisibleRewardsBeforeTasksStart(@Coerce Object teamData, @Coerce Object quest) {
        try {
            return (boolean) quest.getClass().getMethod("isSearchable", teamData.getClass()).invoke(quest, teamData);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("FTB Quests failed to check quest visibility", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("FTB Quests reward lookup integration is incompatible", exception);
        }
    }
}
