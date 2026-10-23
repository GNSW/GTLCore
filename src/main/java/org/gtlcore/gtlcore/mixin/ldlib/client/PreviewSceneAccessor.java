package org.gtlcore.gtlcore.mixin.ldlib.client;

import com.lowdragmc.lowdraglib.gui.widget.SceneWidget;
import com.lowdragmc.lowdraglib.utils.BlockPosFace;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** These fields are stable across LDLib 29b and 33b; their public getters are not. */
@Mixin(value = SceneWidget.class, remap = false)
public interface PreviewSceneAccessor {

    @Accessor("draggable")
    boolean gtlcore$draggable();

    @Accessor("dragging")
    boolean gtlcore$dragging();

    @Accessor("dragging")
    void gtlcore$dragging(boolean dragging);

    @Accessor("clickPosFace")
    void gtlcore$clickPosFace(BlockPosFace clickPosFace);

    @Accessor("useOrtho")
    boolean gtlcore$ortho();

    @Accessor("range")
    float gtlcore$range();
}
