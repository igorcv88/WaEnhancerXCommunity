package com.waenhancer.theme;

/** Structural acceptance shared by expanded and compact native audio layouts. */
public final class VoiceBackingPolicy {
    private VoiceBackingPolicy() { }
    public static boolean player(boolean associated, boolean waveform, boolean timer,
                                 boolean playback, boolean otherAction, boolean contained) {
        return associated && waveform && timer && playback && !otherAction && contained;
    }
}
