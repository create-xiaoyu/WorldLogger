package com.xiaoyu.worldlogger.data;

import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;


/**
 * 玩家死亡来源的数据快照。
 *
 * <p>DamageSource 不一定有攻击者，也不一定有武器。
 * 例如掉虚空、岩浆、摔落都可能没有 source entity，所以这些字段都允许为 null。</p>
 */
public class PlayerDeathData {
    /** 击杀来源名称，例如玩家、实体，或者 null。 */
    public final String sourceName;

    /** 击杀来源坐标；没有来源实体时为 null。 */
    public final String sourcePos;

    /** 击杀来源所在世界；没有来源实体时为 null。 */
    public final String sourceWorld;

    /** 击杀来源使用的物品 ID；没有武器或武器为空时为 null。 */
    public final String sourceItem;

    /** Minecraft 生成的死亡消息文本。 */
    public final String deathMessage;


    /**
     * 读取 DamageSource 中和死亡相关的数据。
     *
     * @param source 死亡事件提供的伤害来源。
     * @param player 死亡玩家，用来生成本地化死亡消息。
     */
    public PlayerDeathData(DamageSource source, ServerPlayer player) {
        // 有些死亡原因没有实体来源，所以必须先判断 null。
        if (source.getEntity() != null) {
            Level level = source.getEntity().level();

            // 下面三项都来自攻击者实体，用于之后查询“谁造成了死亡”。
            this.sourceName = source.getEntity().getName().getString();
            this.sourcePos = StringData.getPos(source.getEntity());
            this.sourceWorld = StringData.getLevelName(level);

            // getWeaponItem 也可能返回 null，所以不能直接读取 item。
            ItemStack weaponItem = source.getWeaponItem();
            this.sourceItem = weaponItem == null || weaponItem.isEmpty()
                    ? null
                    : BuiltInRegistries.ITEM.getKey(weaponItem.getItem()).toString();
        } else {
            this.sourceName = null;
            this.sourcePos = null;
            this.sourceWorld = null;
            this.sourceItem = null;
        }
        // 死亡消息放在最后读取，因为它只依赖 source 和 player，不依赖 source entity 是否存在。
        this.deathMessage = source.getLocalizedDeathMessage(player.getLivingEntity()).getString();
    }
}
