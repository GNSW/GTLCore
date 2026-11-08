package org.gtlcore.gtlcore.client.ftbquests;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class FtbQuestsMissingHighlight {

    private static final long DURATION_MS = 3000L;
    private static final int YELLOW = 0xFFD54F;

    private static long activeUntil;
    private static boolean lookupAttempted;
    private static boolean available;

    private static Class<?> questScreenClass;
    private static Class<?> questButtonClass;
    private static Class<?> questClass;
    private static Class<?> teamDataClass;
    private static Field questPanelField;
    private static Field questField;
    private static Field questScreenField;
    private static Field questScreenFileField;
    private static Field selfTeamDataField;
    private static Object clientQuestFile;
    private static Method getWidgetsMethod;
    private static Method getXMethod;
    private static Method getYMethod;
    private static Method getWidthMethod;
    private static Method getHeightMethod;
    private static Method questDepsCompleteMethod;
    private static Method isCompletedMethod;

    private FtbQuestsMissingHighlight() {}

    public static boolean isQuestScreen(Screen screen) {
        return lookup() && questScreenClass.isInstance(screen);
    }

    public static void activate() {
        activeUntil = System.currentTimeMillis() + DURATION_MS;
        org.gtlcore.gtlcore.GTLCore.LOGGER.info("FTB Quests missing-quest highlight activated");
    }

    public static void drawFor(Object button, GuiGraphics graphics, int x, int y, int width, int height) {
        if (System.currentTimeMillis() >= activeUntil || !lookup()) {
            return;
        }
        try {
            Object questScreen = questScreenField.get(button);
            Object file = questScreen == null ? null : questScreenFileField.get(questScreen);
            Object teamData = file == null ? null : selfTeamDataField.get(file);
            if (teamData == null) {
                return;
            }
            Object quest = questField.get(button);
            if (quest == null || (boolean) isCompletedMethod.invoke(teamData, quest) ||
                    !(boolean) questDepsCompleteMethod.invoke(quest, teamData)) {
                return;
            }
            float pulse = (float) ((Math.sin((System.currentTimeMillis() % 800L) / 800.0 * Math.PI * 2) + 1) / 2);
            int innerColor = ((int) (150 + 105 * pulse) << 24) | YELLOW;
            int outerColor = ((int) (50 + 80 * pulse) << 24) | YELLOW;
            int innerX = x - 1;
            int innerY = y - 1;
            int innerW = width + 2;
            int innerH = height + 2;
            graphics.fill(innerX, innerY, innerX + innerW, innerY + 1, innerColor);
            graphics.fill(innerX, innerY + innerH - 1, innerX + innerW, innerY + innerH, innerColor);
            graphics.fill(innerX, innerY, innerX + 1, innerY + innerH, innerColor);
            graphics.fill(innerX + innerW - 1, innerY, innerX + innerW, innerY + innerH, innerColor);
            int outerX = x - 3;
            int outerY = y - 3;
            int outerW = width + 6;
            int outerH = height + 6;
            graphics.fill(outerX, outerY, outerX + outerW, outerY + 1, outerColor);
            graphics.fill(outerX, outerY + outerH - 1, outerX + outerW, outerY + outerH, outerColor);
            graphics.fill(outerX, outerY, outerX + 1, outerY + outerH, outerColor);
            graphics.fill(outerX + outerW - 1, outerY, outerX + outerW, outerY + outerH, outerColor);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            org.gtlcore.gtlcore.GTLCore.LOGGER.warn("Failed to draw FTB Quests missing quest highlight", exception);
        }
    }

    private static boolean lookup() {
        if (lookupAttempted) {
            return available;
        }
        lookupAttempted = true;
        try {
            questScreenClass = Class.forName("dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen");
            questButtonClass = Class.forName("dev.ftb.mods.ftbquests.client.gui.quests.QuestButton");
            questClass = Class.forName("dev.ftb.mods.ftbquests.quest.Quest");
            teamDataClass = Class.forName("dev.ftb.mods.ftbquests.quest.TeamData");
            Class<?> panelClass = Class.forName("dev.ftb.mods.ftbquests.client.gui.quests.QuestPanel");
            Class<?> clientQuestFileClass = Class.forName("dev.ftb.mods.ftbquests.client.ClientQuestFile");

            questPanelField = questScreenClass.getField("questPanel");
            questField = questButtonClass.getDeclaredField("quest");
            questField.setAccessible(true);
            questScreenField = questButtonClass.getDeclaredField("questScreen");
            questScreenField.setAccessible(true);
            questScreenFileField = questScreenClass.getDeclaredField("file");
            questScreenFileField.setAccessible(true);
            selfTeamDataField = clientQuestFileClass.getField("selfTeamData");
            clientQuestFile = clientQuestFileClass.getField("INSTANCE").get(null);

            getWidgetsMethod = panelClass.getMethod("getWidgets");
            Class<?> widgetClass = Class.forName("dev.ftb.mods.ftblibrary.ui.Widget");
            getXMethod = widgetClass.getMethod("getX");
            getYMethod = widgetClass.getMethod("getY");
            getWidthMethod = widgetClass.getMethod("getWidth");
            getHeightMethod = widgetClass.getMethod("getHeight");

            questDepsCompleteMethod = questClass.getMethod("areDependenciesComplete", teamDataClass);
            Class<?> questObjectClass = Class.forName("dev.ftb.mods.ftbquests.quest.QuestObject");
            isCompletedMethod = teamDataClass.getMethod("isCompleted", questObjectClass);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            available = false;
            org.gtlcore.gtlcore.GTLCore.LOGGER.warn("Unable to resolve FTB Quests highlight API", exception);
        }
        return available;
    }
}
