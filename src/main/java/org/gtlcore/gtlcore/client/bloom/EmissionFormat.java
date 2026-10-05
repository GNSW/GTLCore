package org.gtlcore.gtlcore.client.bloom;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/** Same element layout as BLOCK, but privately owned. Iris only extends the shared vanilla format instances. */
final class EmissionFormat {

    static final VertexFormat BLOCK = new VertexFormat(ImmutableMap.of(
            "Position", DefaultVertexFormat.ELEMENT_POSITION,
            "Color", DefaultVertexFormat.ELEMENT_COLOR,
            "UV0", DefaultVertexFormat.ELEMENT_UV0,
            "UV2", DefaultVertexFormat.ELEMENT_UV2,
            "Normal", DefaultVertexFormat.ELEMENT_NORMAL,
            "Padding", DefaultVertexFormat.ELEMENT_PADDING));

    private EmissionFormat() {}
}
