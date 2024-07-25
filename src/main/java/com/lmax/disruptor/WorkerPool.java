/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import com.lmax.disruptor.util.Util;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每个WorkHandler被封装为WorkProcessor, 该对象为 一组 WorkProcessor的集合: Worker池
 */
public final class WorkerPool<T> {
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** WorkProcessor 数组: 其实也就是用户的WorkHandler数组. **/
    private final WorkProcessor<?>[] workProcessors;

    /** 消费的位点. **/
    private final Sequence workSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    /** 存储结构. **/
    private final RingBuffer<T> ringBuffer;

    /**
     * @param ringBuffer       存储容器.
     * @param sequenceBarrier  当前组的消费屏障, 存有上一消费者组的消费信息, 可保证消费者组之间消费的顺序性.
     * @param workHandlers     就你写的那个.
     */
    @SafeVarargs
    public WorkerPool(final RingBuffer<T> ringBuffer, final SequenceBarrier sequenceBarrier, final ExceptionHandler<? super T> exceptionHandler, final WorkHandler<? super T>... workHandlers) {
        this.ringBuffer = ringBuffer;
        final int numWorkers = workHandlers.length;
        workProcessors = new WorkProcessor[numWorkers];

        for (int i = 0; i < numWorkers; i++) {
            workProcessors[i] = new WorkProcessor<>(ringBuffer, sequenceBarrier, workHandlers[i], exceptionHandler, workSequence);
        }
    }

    /**
     * 每个 WorkProcessor 关联的Sequence + 当前对象的workSequence(消费组共享)
     */
    public Sequence[] getWorkerSequences() {
        final Sequence[] sequences = new Sequence[workProcessors.length + 1];
        for (int i = 0, size = workProcessors.length; i < size; i++) {
            sequences[i] = workProcessors[i].getSequence();
        }
        sequences[sequences.length - 1] = workSequence;

        return sequences;
    }

    /**
     * 依次启动工作池处理事件, 说白了, 就俩字儿: 牡丹红, 出来接客啦 ～
     *
     * @param executor providing threads for running the workers.
     * @return the {@link RingBuffer} used for the work queue.
     * @throws IllegalStateException if the pool has already been started and not halted yet
     */
    public RingBuffer<T> start(final Executor executor) {
        //注意: 这里上来就设置标识, 如果在最后一行设置标识, 多线程启动时, 标识不会重复设置, 但方法里面的逻辑, 却已经重复执行了
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("WorkerPool has already been started and cannot be restarted until halted.");
        }

        /**
         * 得到 生产者游标, 设为 当前组共享的 workSequence 的游标: 这也就是说, 在此之前发布的数据, 就不要了
         * 其实Kafka的消费者组, 在上线时又何尝不是这样呢,
         */
        final long cursor = ringBuffer.getCursor();
        workSequence.orderedSet(cursor);

        //设置每个消费者的游标位置(在此之前生产的数据, 不再消费), 并启动
        for (WorkProcessor<?> processor : workProcessors) {
            processor.getSequence().orderedSet(cursor);
            executor.execute(processor);
        }

        return ringBuffer;
    }

    /**
     * Wait for the {@link RingBuffer} to drain of published events then halt the workers.
     */
    public void drainAndHalt() {
        Sequence[] workerSequences = getWorkerSequences();
        while (ringBuffer.getCursor() > Util.getMinimumSequence(workerSequences)) {
            Thread.yield();
        }

        for (WorkProcessor<?> processor : workProcessors) {
            processor.halt();
        }

        started.set(false);
    }

    /**
     * Halt all workers immediately at the end of their current cycle.
     */
    public void halt() {
        for (WorkProcessor<?> processor : workProcessors) {
            processor.halt();
        }

        started.set(false);
    }

    public boolean isRunning() { return started.get(); }
}
