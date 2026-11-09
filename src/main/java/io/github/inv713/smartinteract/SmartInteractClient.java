package io.github.inv713.smartinteract;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** 客户端初始化，拆出来避免专用服务端加载客户端类。 */
@EventBusSubscriber(modid = SmartInteract.MODID, value = Dist.CLIENT)
public final class SmartInteractClient {

    // 默认 ~ 键，可在控制里改
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
