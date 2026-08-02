package com.glow.teammates;

/**
 * Shared glow flag constants.
 *
 * <p>Deliberately a plain class, not a mixin interface: interface mixins
 * inject their fields into the target class, so constants defined in
 * {@code @Mixin} interfaces fail validation unless they are {@code @Shadow}.
 */
public final class GlowConstants {
    private GlowConstants() {}

    /** Bit for the GLOWING shared flag ({@code 1 << Entity.FLAG_GLOWING}). */
    public static final int FLAG_GLOWING = 0x40;

    /** Clear mask for the glow bit; keeps every other shared flag bit intact. */
    public static final int GLOW_CLEAR_MASK = 0xBF;
}
