package com.xiaoyu.worldlogger.mysql;

import com.xiaoyu.worldlogger.Config;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MySQL 异步线程池。
 *
 * <p>Minecraft 服务器主线程负责游戏刻、实体、方块和命令。如果直接在主线程查询或写入 MySQL，
 * 数据量大或数据库卡顿时就会导致服务器 TPS 下降。因此所有数据库读写都应该提交到这里执行。</p>
 */
public class MySQLExecutorService {
    /** 全局数据库线程池。为 null 表示尚未启动或已经关闭。 */
    private static ExecutorService executor;

    /**
     * 初始化线程池。
     * <p>
     * 用法：服务器启动且数据库连接池初始化成功后调用。
     */
    public static void init() {
        // 先关闭旧线程池，防止重载或重复启动时残留旧线程。
        shutdown();

        // 配置值至少为 1，Math.max 是第二道保护。
        int threadCount = Math.max(1, Config.THREAD_NUMBER.get());

        // 创建固定大小线程池，线程名由 namedThreadFactory 生成，方便在日志或调试器中识别。
        executor = Executors.newFixedThreadPool(threadCount, namedThreadFactory());
    }

    /**
     * 取得原始 ExecutorService。
     *
     * @return 当前线程池；如果未初始化则可能为 null。
     *
     * 注意：查询代码使用 CompletableFuture 时需要直接传入 ExecutorService。
     */
    public static ExecutorService getExecutor() {
        return executor;
    }

    /**
     * 安全提交数据库任务。
     *
     * @param logger 调用方日志对象，用于输出失败信息。
     * @param taskName 任务名称，写进日志时能看出是哪类 SQL 失败。
     * @param task 真正要执行的数据库任务。
     * @return true 表示任务成功进入线程池；false 表示线程池不可用或拒绝任务。
     */
    public static boolean execute(Logger logger, String taskName, Runnable task) {
        // 复制到局部变量，避免检查 executor 后它被其他线程改成 null。
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown()) {
            logger.warn("Skipped MySQL task '{}' because the database executor is not running.", taskName);
            return false;
        }

        try {
            // execute 会立刻返回，真正的 SQL 会在线程池中的工作线程里执行。
            currentExecutor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            logger.error("MySQL executor rejected task '{}'.", taskName, e);
            return false;
        }
    }

    /**
     * 关闭线程池。
     * <p>
     * 用法：服务器关闭时调用。它会先等待任务自然完成，超时后再强制关闭。
     */
    public static void shutdown() {
        if (executor != null) {
            // shutdown 表示不再接收新任务，但已经提交的任务会继续执行。
            executor.shutdown();
            try {
                // 最多等待 10 秒，让正在进行的数据库写入尽量完成。
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    // 超时后强制中断任务，避免服务器关闭流程一直卡住。
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                // 如果当前线程被中断，也要尝试关闭线程池，并恢复中断标记。
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                executor = null;
            }
        }
    }

    /**
     * 创建带名字的线程工厂。
     *
     * @return ThreadFactory，用于给线程池中的线程命名。
     */
    private static ThreadFactory namedThreadFactory() {
        // AtomicInteger 是线程安全计数器，用来生成 1、2、3 这样的线程编号。
        AtomicInteger index = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "WorldLogger-MySQL-" + index.getAndIncrement());

            // false 表示这些线程不是守护线程，关闭时由 shutdown 明确管理。
            thread.setDaemon(false);
            return thread;
        };
    }
}
