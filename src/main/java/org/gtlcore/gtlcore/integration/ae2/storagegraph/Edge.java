package org.gtlcore.gtlcore.integration.ae2.storagegraph;

public record Edge(long id, long fromNode, long toNode, EdgeKind kind, PolicySnapshot policy, int nativeOrder) {}
