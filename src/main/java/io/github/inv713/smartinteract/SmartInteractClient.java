package io.github.inv713.smartinteract;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * 所有客户端专用的初始化都放在这里。把 KeyMapping 从 {@code @Mod} 类里拆出来，
 * 避免专用服务端加载时找不到客户端类。
 */
@EventBusSubscriber(modid = SmartInteract.MODID, value = Dist.CLIENT)
public final class SmartInteractClient {

    /** 默认是 ~ 键（grave，数字 1 左边）。可在 选项 → 控制 → 智能物品切换 里改。 */
    public static final KeyMapping OPEN_KEY = new KeyMapping(
        "key.smartinteract.open",
        InputConstants.KEY_GRAVE,
        "key.categories.smartinteract"
    );

    private SmartInteractClient() {}

    @SubscribeEvent
    static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEY);
    }

    @SubscribeEvent
    static void onKeyInput(InputEvent.Key event) {
        if (OPEN_KEY.consumeClick()) {
            Minecraft.getInstance().setScreen(new MappingScreen(null));
        }
    }
}
