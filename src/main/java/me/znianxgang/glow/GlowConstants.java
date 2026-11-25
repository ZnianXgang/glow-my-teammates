package me.znianxgang.glow;

/**
 * Shared glow-flag bits.
 *
 * <p>A plain class on purpose: constants declared in a mixin interface are
 * injected into the target class and fail validation.
 */
public final class GlowConstants {
    private GlowConstants() {}

    /** {@code 1 << Entity.FLAG_GLOWING}. */
    public static final int FLAG_GLOWING = 0x40;

    /** Strips the glow bit, keeping the other shared-flag bits. */
    public static final int GLOW_CLEAR_MASK = 0xBF;
}
