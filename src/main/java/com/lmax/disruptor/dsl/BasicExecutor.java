package com.lmax.disruptor.dsl;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

public class BasicExecutor implements Executor {
    private final ThreadFactory factory;
    private final Queue<Thread> threads = new ConcurrentLinkedQueue<>();

    public BasicExecutor(ThreadFactory factory) {
        this.factory = factory;
    }

    @Override
    public void execute(Runnable command)
    {
        final Thread thread = factory.newThread(command);
        if (null == thread)
        {
            throw new RuntimeException("Failed to create thread to run: " + command);
        }

        thread.start();

        threads.add(thread);
    }

}
