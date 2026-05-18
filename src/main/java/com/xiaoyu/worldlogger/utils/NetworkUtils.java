package com.xiaoyu.worldlogger.utils;

import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class NetworkUtils {
    public static String getPlayerIP (ServerPlayer serverPlayer) {
        SocketAddress socketAddress = serverPlayer.connection.getRemoteAddress();
        if (!(socketAddress instanceof InetSocketAddress inetSocketAddress)) return null;

        return inetSocketAddress.getAddress().getHostAddress();
    }
}
