package com.xiaoyu.worldlogger.mysql;

import com.xiaoyu.worldlogger.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MySQLExecutorService {
    private static ExecutorService executor;

    public static void init() {
        executor = Executors.newFixedThreadPool(Config.THREAD_NUMBER.get());
    }

    public static ExecutorService getExecutor() {
        return executor;
    }

    public static void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                executor = null;
            }
        }
    }
}
