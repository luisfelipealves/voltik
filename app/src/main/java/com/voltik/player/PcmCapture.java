package com.voltik.player;

import java.nio.ByteBuffer;

/**
 * A bounded single-producer/multiple-reader PCM tap. The audio render thread is
 * the sole producer; UI/rendering threads may read snapshots concurrently.
 * Input is signed 16-bit little-endian interleaved PCM. This class only reads
 * source bytes; it never mutates the audio buffer or controls playback.
 */
public final class PcmCapture {
    private static final int CAPACITY = 4096;
    private static final int MASK = CAPACITY - 1;
    private final float[] ring = new float[CAPACITY];
    private volatile boolean enabled;
    private volatile long committed;
    private volatile long visibleFrom;
    private volatile int sampleRateHz;

    /** Disabled by default. Enabling begins a fresh visible capture. */
    public void setEnabled(boolean enable) {
        if (enable && !enabled) visibleFrom = committed;
        enabled = enable;
    }

    /** Called on the audio render thread; never allocates or blocks. */
    public void accept(ByteBuffer pcm, int channels) {
        if (!enabled || pcm == null || channels <= 0 || channels > 32) return;
        final int frameBytes = channels * 2;
        final int base = pcm.position();
        final int frames = pcm.remaining() / frameBytes;
        if (frames == 0) return;
        long next = committed;
        for (int frame = 0; frame < frames; frame++) {
            int offset = base + frame * frameBytes;
            int total = 0;
            for (int channel = 0; channel < channels; channel++) {
                int index = offset + channel * 2;
                int value = (short) ((pcm.get(index) & 255) | (pcm.get(index + 1) << 8));
                total += value;
            }
            ring[(int) (next++ & MASK)] = total / (32768f * channels);
        }
        committed = next;
    }

    /** Called on the audio render thread after format configuration. */
    public void setSampleRateHz(int rate) { sampleRateHz = Math.max(rate, 0); }
    public int getSampleRateHz() { return sampleRateHz; }

    /** Hide old audio at a track boundary, flush, reset, or release. */
    public void clear() { visibleFrom = committed; }

    /**
     * Snapshot newest samples in chronological order, allocating only on the
     * consumer thread. A concurrent writer can overwrite old ring elements:
     * consumers must treat frames as approximate, not as a lossless recording.
     */
    public float[] snapshot(int maximum) {
        if (!enabled || maximum <= 0) return new float[0];
        long end = committed;
        long start = Math.max(visibleFrom, end - CAPACITY);
        int count = (int) Math.min(Math.min((long) maximum, CAPACITY), Math.max(0, end - start));
        float[] result = new float[count];
        long first = end - count;
        for (int i = 0; i < count; i++) result[i] = ring[(int) ((first + i) & MASK)];
        return result;
    }
}
