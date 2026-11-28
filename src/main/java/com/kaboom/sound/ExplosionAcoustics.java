package com.kaboom.sound;

public class ExplosionAcoustics {
    public static double radius(double energy) {
        return Math.min(
                1024.0,
                48.0 + 12.0 * Math.sqrt(Math.max(0.0, energy))
        );
    }

    public static float volume(double distance, double energy) {
        double range = radius(energy);

        double fraction = Math.max(
                0.0,
                1.0 - distance / range
        );

        return (float) Math.pow(fraction, 1.5);
    }

    public static float pitch(double distance, double energy) {
        double fraction = Math.min(
                1.0,
                Math.max(0.0, distance / radius(energy))
        );

        return (float) (1.0 - 0.12 * fraction);
    }
}
