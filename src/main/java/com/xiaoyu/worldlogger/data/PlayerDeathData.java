package com.xiaoyu.worldlogger.data;

import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;


public class PlayerDeathData {
    public final String sourceName;
    public final String sourcePos;
    public final String sourceWorld;
    public final String sourceItem;
    public final String deathMessage;


    public PlayerDeathData(DamageSource source, ServerPlayer player) {
        if (source.getEntity() != null) {
            Level level = source.getEntity().level();

            this.sourceName = source.getEntity().getName().getString();
            this.sourcePos = StringData.getPos(source.getEntity());
            this.sourceWorld = StringData.getLevelName(level);
            this.sourceItem = BuiltInRegistries.ITEM.getKey(source.getWeaponItem().getItem()).toString();
        } else {
            this.sourceName = null;
            this.sourcePos = null;
            this.sourceWorld = null;
            this.sourceItem = null;
        }
        this.deathMessage = source.getLocalizedDeathMessage(player.getLivingEntity()).getString();
    }
}
