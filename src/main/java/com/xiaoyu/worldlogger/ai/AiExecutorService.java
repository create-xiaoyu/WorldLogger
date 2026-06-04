package com.xiaoyu.worldlogger.ai;

import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 请求专用线程池。
 *
 * <p>AI HTTP 请求可能比数据库查询更慢，因此不应该占用 Minecraft 主线程。
 * 这里使用独立线程池，避免长时间等待 OpenAI 响应时阻塞游戏逻辑。</p>
 */
public final class AiExecutorService {
    /** AI 任务线程池。 */
    private static ExecutorService executor;

    /** 工具类不需要实例化。 */
    private AiExecutorService() {}

    /** 懒加载初始化线程池。 */
    private static synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(2, threadFactory());
        }
        return executor;
    }

    /**
     * 提交 AI 任务。
     *
     * @param logger 日志对象。
     * @param taskName 任务名称。
     * @param task 要执行的任务。
     * @return true 表示任务已进入线程池。
     */
    public static boolean execute(Logger logger, String taskName, Runnable task) {
        try {
            executor().execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            logger.error("WorldLogger AI executor rejected task '{}'.", taskName, e);
            return false;
        }
    }

    /** 关闭 AI 线程池。服务器关闭时调用。 */
    public static synchronized void shutdown() {
        if (executor == null) {
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor = null;
        }
    }

    /** 创建带名字的守护线程，方便调试并避免客户端退出时残留非守护线程。 */
    private static ThreadFactory threadFactory() {
        AtomicInteger index = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "WorldLogger-AI-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
