package com.xiaoyu.worldlogger.client;

import com.xiaoyu.worldlogger.client.screen.WorldLoggerGuiScreen;
import com.xiaoyu.worldlogger.network.payload.GuiTableResponsePayload;

/**
 * 客户端专用桥接类。
 *
 * <p>WorldLoggerNetwork 属于 common 代码，专用服务器也会加载它。
 * 如果 common 代码直接 import Minecraft 客户端类，专用服务器可能因为找不到客户端类而崩溃。
 * 所以网络类通过反射调用这个桥接类，把真正的客户端 GUI 操作放到 client 包中。</p>
 */
public final class WorldLoggerClientHooks {
    /** 工具类不需要实例化。 */
    private WorldLoggerClientHooks() {}

    /**
     * 处理 GUI 查询响应。
     *
     * @param payload 服务器返回的 GUI 表格数据。
     */
    public static void handleGuiTableResponse(GuiTableResponsePayload payload) {
        WorldLoggerGuiScreen.handleResponse(payload);
    }
}
