package com.example.common.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 虚拟线程小工具。
 */
public final class VirtualThreadExecutors {

    /**
     * 带名字前缀的"每任务一虚拟线程"执行器。
     * 虚拟线程默认无名（%thread 打印为空），给个前缀后日志里能看到
     * order-vc-1、order-vc-2 这样的线程名，演示效果直观。
     *
     * <p>返回的 ExecutorService 实现了 AutoCloseable（JDK 19+），
     * 可以用 try-with-resources：close() 会等所有任务跑完。
     */
    public static ExecutorService namedVirtualPerTask(String prefix) {
        ThreadFactory factory = Thread.ofVirtual().name(prefix, 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    private VirtualThreadExecutors() {
    }
}
