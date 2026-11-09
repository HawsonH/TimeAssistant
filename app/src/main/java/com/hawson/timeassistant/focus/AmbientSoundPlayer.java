/**
 * 专注环境音播放器
 *
 * 在专注进行中循环播放本机生成的 Brown Noise，无需网络或读取用户媒体文件。
 */
package com.hawson.timeassistant.focus;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;

/** Local ambient-noise loop during a running focus round. */
public final class AmbientSoundPlayer {
    private static final int SAMPLE_RATE = 22_050;
    private static final float OUTPUT_VOLUME = 0.10f;

    private AudioTrack track;

    public synchronized void start() {
        if (track != null) return;
        AudioTrack created = null;
        try {
            int minimumBytes = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int sampleCount = Math.max(SAMPLE_RATE * 3, Math.max(1, minimumBytes / 2));
            short[] samples = createBrownNoise(sampleCount);
            created = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.length * 2)
                    .build();
            int written = created.write(samples, 0, samples.length);
            if (written != samples.length) {
                created.release();
                return;
            }
            created.setLoopPoints(0, samples.length, -1);
            created.setVolume(OUTPUT_VOLUME);
            created.play();
            track = created;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            if (created != null) {
                try {
                    created.release();
                } catch (RuntimeException ignoredRelease) {
                    // Audio is an optional aid; an unavailable device must not interrupt focus.
                }
            }
        }
    }

    public synchronized void stop() {
        AudioTrack active = track;
        track = null;
        if (active == null) return;
        try {
            if (active.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) active.stop();
        } catch (IllegalStateException ignored) {
            // The audio route may disappear while the activity is stopping.
        } finally {
            active.release();
        }
    }

    synchronized boolean isPlaying() {
        return track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    private static short[] createBrownNoise(int sampleCount) {
        short[] samples = new short[sampleCount];
        Random random = new Random();
        double level = 0;
        for (int index = 0; index < samples.length; index++) {
            double white = random.nextDouble() * 2.0 - 1.0;
            level = (level + 0.018 * white) / 1.018;
            samples[index] = (short) Math.max(
                    Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, level * 9_000)
            );
        }
        return samples;
    }
}
