package org.gtlcore.gtlcore.common.block;

/** The music-locked squash-and-rebound curve used by the plushies. */
public final class PlushieAnimation {

    public static final double LENGTH = 0.91667;
    public static final int MUSIC_FRAMES = 2482560;
    public static final int MUSIC_SAMPLE_RATE = 44100;
    public static final double MUSIC_LENGTH = (double) MUSIC_FRAMES / MUSIC_SAMPLE_RATE;
    private static final double[] TIMES = { 0, 0.25, 0.45833, 0.70833, LENGTH };
    private static final double[] VALUES = { 0, 1, 0, 1, 0 };
    private static final float WIDTH_PERCENT = 50.0F;
    private static final float HEIGHT_PERCENT = 50.0F;

    private PlushieAnimation() {}

    public static double phase(double seconds) {
        return Math.max(0, seconds) % LENGTH;
    }

    public static float squash(double seconds) {
        double time = phase(seconds);
        int frame = 0;
        while (frame < TIMES.length - 2 && time > TIMES[frame + 1]) frame++;
        double progress = (time - TIMES[frame]) / (TIMES[frame + 1] - TIMES[frame]);
        return (float) (VALUES[frame] + (VALUES[frame + 1] - VALUES[frame]) * progress * progress * (3 - 2 * progress));
    }

    public static float musicSquash(double seconds) {
        double time = Math.max(0, seconds) % MUSIC_LENGTH;
        float value = squash(time);
        double tail = (time - (MUSIC_LENGTH - 0.1)) / 0.1;
        if (tail > 0) value *= (float) (1 - tail * tail * (3 - 2 * tail));
        return value;
    }

    public static float widthScale(float squash) {
        return 1 + WIDTH_PERCENT / 100.0F * squash;
    }

    public static float heightScale(float squash) {
        return 1 - HEIGHT_PERCENT / 100.0F * squash;
    }

    public static long musicFrame(long elapsedTicks) {
        return Math.floorMod(Math.floorMod(elapsedTicks, MUSIC_FRAMES) * (MUSIC_SAMPLE_RATE / 20), MUSIC_FRAMES);
    }
}
