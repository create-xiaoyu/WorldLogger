package com.xiaoyu.worldlogger.utils;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具类。
 *
 * <p>当前主要用于把“玩家 UUID + 玩家名”转换成较短且固定的 key，
 * 方便在 Map 中保存玩家临时状态，例如最近右键的方块或最近一次死亡时间。</p>
 */
public class HashUtils {
    /** 日志对象，用于 SHA-1 算法不可用时输出错误。正常 Java 环境几乎不会发生这种情况。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 计算字符串的 SHA-1 值。
     *
     * @param send 原始字符串。
     * @return 40 位十六进制 SHA-1 字符串；极少数情况下算法不可用会返回 null。
     */
    public static String sha1(String send) {
        try {
            // MessageDigest 是 Java 标准库提供的哈希算法入口。
            MessageDigest hash = MessageDigest.getInstance("SHA-1");

            // StringBuilder 用于高效拼接每个字节转换后的十六进制文本。
            StringBuilder stringBuilder = new StringBuilder();

            // digest 返回字节数组，每个字节用两位十六进制表示，保证长度固定。
            for (byte b : hash.digest(send.getBytes())) {
                stringBuilder.append(String.format("%02x", b));
            }

            return stringBuilder.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 是标准算法，理论上总存在；这里保留日志是为了防御异常运行环境。
            LOGGER.error("SHA-1 Error", e);
            return null;
        }
    }
}
