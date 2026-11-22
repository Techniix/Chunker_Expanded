package com.hivemc.chunker.conversion.merge;

/**
 * Small, dependency free, deterministic noise helpers used by the merge generators. Everything is derived from a seed
 * and absolute block co-ordinates so the output is identical no matter which order columns are processed in.
 */
public final class MergeNoise {
    private final long seed;

    /**
     * Create a noise source.
     *
     * @param seed the seed to derive all values from.
     */
    public MergeNoise(long seed) {
        this.seed = seed;
    }

    /**
     * Hash co-ordinates into a 64-bit value (SplitMix64 style finaliser).
     */
    public static long hash(long seed, long x, long y, long z) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h ^= x * 0xBF58476D1CE4E5B9L;
        h = Long.rotateLeft(h, 27) * 0x94D049BB133111EBL;
        h ^= y * 0xD6E8FEB86659FD93L;
        h = Long.rotateLeft(h, 31) * 0xBF58476D1CE4E5B9L;
        h ^= z * 0x94D049BB133111EBL;
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return h;
    }

    /**
     * A uniformly distributed value in [0, 1) for a lattice point.
     */
    public double random(long salt, long x, long y, long z) {
        return (hash(seed + salt, x, y, z) >>> 11) * 0x1.0p-53;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Smooth 3D value noise in [-1, 1].
     *
     * @param salt a salt so different features don't share the same noise.
     */
    public double noise3(long salt, double x, double y, double z) {
        long x0 = (long) Math.floor(x), y0 = (long) Math.floor(y), z0 = (long) Math.floor(z);
        double fx = fade(x - x0), fy = fade(y - y0), fz = fade(z - z0);
        double c000 = random(salt, x0, y0, z0), c100 = random(salt, x0 + 1, y0, z0);
        double c010 = random(salt, x0, y0 + 1, z0), c110 = random(salt, x0 + 1, y0 + 1, z0);
        double c001 = random(salt, x0, y0, z0 + 1), c101 = random(salt, x0 + 1, y0, z0 + 1);
        double c011 = random(salt, x0, y0 + 1, z0 + 1), c111 = random(salt, x0 + 1, y0 + 1, z0 + 1);
        double v = lerp(
                lerp(lerp(c000, c100, fx), lerp(c010, c110, fx), fy),
                lerp(lerp(c001, c101, fx), lerp(c011, c111, fx), fy),
                fz
        );
        return v * 2 - 1;
    }

    /**
     * Smooth 2D value noise in [-1, 1].
     */
    public double noise2(long salt, double x, double z) {
        return noise3(salt, x, 0.5, z);
    }

    /**
     * Fractal (fBm) 2D noise in roughly [-1, 1].
     */
    public double fbm2(long salt, double x, double z, int octaves) {
        double total = 0, amplitude = 1, frequency = 1, norm = 0;
        for (int i = 0; i < octaves; i++) {
            total += noise2(salt + i * 7919L, x * frequency, z * frequency) * amplitude;
            norm += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        return total / norm;
    }

    public long getSeed() {
        return seed;
    }
}
