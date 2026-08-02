package com.glow.teammates.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {
    /** Bit for the GLOWING shared flag ({@code 1 << Entity.FLAG_GLOWING}). */
    int FLAG_GLOWING = 0x40;

    /** Clear mask for the glow bit; keeps every other shared flag bit intact. */
    int GLOW_CLEAR_MASK = 0xBF;

    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> getSharedFlagsId() {
        throw new AssertionError("Mixin not applied");
    }
}
