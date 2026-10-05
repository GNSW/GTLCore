package org.gtlcore.gtlcore.client.bloom;

/** Implemented only on audited Oculus pipelines; keeps deleted texture IDs out of live samplers. */
public interface PbrSamplerState {

    void gtlcore$bloom$resetPbrSamplers(int normal, int specular);
}
