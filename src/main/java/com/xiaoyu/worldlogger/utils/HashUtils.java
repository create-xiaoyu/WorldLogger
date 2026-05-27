package com.xiaoyu.worldlogger.utils;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtils {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static String sha1(String send) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-1");
            StringBuilder stringBuilder = new StringBuilder();

            for (byte b : hash.digest(send.getBytes())) {
                stringBuilder.append(String.format("%02x", b));
            }

            return stringBuilder.toString();
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error("SHA-1 Error", e);
            return null;
        }
    }
}
