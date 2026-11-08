package io.github.inv713.smartinteract;

import net.neoforged.fml.common.Mod;

/**
 * 模组入口。这里不引用任何客户端类，所以 jar 放在专用服务端也不会因类加载失败而崩溃
 * （模组本身只在客户端生效）。
 */
@Mod(SmartInteract.MODID)
public class SmartInteract {

    public static final String MODID = "smartinteract";

    public SmartInteract() {}
}
