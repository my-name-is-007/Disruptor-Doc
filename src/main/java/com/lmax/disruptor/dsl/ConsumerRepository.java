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
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.*;

import java.util.*;

/**
 * Provides a repository mechanism to associate {@link EventHandler}s with {@link EventProcessor}s
 *
 * 从名称可以看出, 将当前Disruptor下面的Sequence、事件处理器 等信息, 全部 关联起来.
 * 与生产消费的主流程无关,
 * @param <T> the type of the {@link EventHandler}
 */
class ConsumerRepository<T> implements Iterable<ConsumerInfo> {

    /**
     * {"用户编写的事件处理器" : "处理器所属的EventProcessorInfo"}
     */
    private final Map<EventHandler<?>, EventProcessorInfo<T>> eventProcessorInfoByEventHandler = new IdentityHashMap<>();

    /**
     * {"消费者所关联的Sequence": "消费者的ConsumerInfo"}.
     */
    private final Map<Sequence, ConsumerInfo> eventProcessorInfoBySequence = new IdentityHashMap<>();

    /**
     * 所有 ConsumerInfo(WorkPool、BatchEventProcessor) 的集合,
     */
    private final Collection<ConsumerInfo> consumerInfos = new ArrayList<>();

    /** 添加 EventProcessor, 但我看着是添加的 BatchEventProcessor. **/
    public void add(final EventProcessor eventprocessor, final EventHandler<? super T> handler, final SequenceBarrier barrier) {
        final EventProcessorInfo<T> consumerInfo = new EventProcessorInfo<>(eventprocessor, handler, barrier);
        eventProcessorInfoByEventHandler.put(handler, consumerInfo);
        eventProcessorInfoBySequence.put(eventprocessor.getSequence(), consumerInfo);
        consumerInfos.add(consumerInfo);
    }

    public void add(final EventProcessor processor) {
        final EventProcessorInfo<T> consumerInfo = new EventProcessorInfo<>(processor, null, null);
        eventProcessorInfoBySequence.put(processor.getSequence(), consumerInfo);
        consumerInfos.add(consumerInfo);
    }

    public void add(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier) {
        final WorkerPoolInfo<T> workerPoolInfo = new WorkerPoolInfo<>(workerPool, sequenceBarrier);
        consumerInfos.add(workerPoolInfo);
        for (Sequence sequence : workerPool.getWorkerSequences()) {
            eventProcessorInfoBySequence.put(sequence, workerPoolInfo);
        }
    }

    /**
     * 获取最后一组消费者的 Sequence数组, WorkPool的话, 会把 workSequence也给追加进去,
     * @param includeStopped
     * @return
     */
    public Sequence[] getLastSequenceInChain(boolean includeStopped) {
        List<Sequence> lastSequence = new ArrayList<>();
        for (ConsumerInfo consumerInfo : consumerInfos) {
            if ((includeStopped || consumerInfo.isRunning()) && consumerInfo.isEndOfChain()) {
                final Sequence[] sequences = consumerInfo.getSequences();
                Collections.addAll(lastSequence, sequences);
            }
        }

        return lastSequence.toArray(new Sequence[lastSequence.size()]);
    }

    public EventProcessor getEventProcessorFor(final EventHandler<T> handler) {
        final EventProcessorInfo<T> eventprocessorInfo = getEventProcessorInfo(handler);
        if (eventprocessorInfo == null) {
            throw new IllegalArgumentException("The event handler " + handler + " is not processing events.");
        }

        return eventprocessorInfo.getEventProcessor();
    }

    public Sequence getSequenceFor(final EventHandler<T> handler) {
        return getEventProcessorFor(handler).getSequence();
    }

    public void unMarkEventProcessorsAsEndOfChain(final Sequence... barrierEventProcessors) {
        for (Sequence barrierEventProcessor : barrierEventProcessors) {
            getEventProcessorInfo(barrierEventProcessor).markAsUsedInBarrier();
        }
    }

    @Override
    public Iterator<ConsumerInfo> iterator() {
        return consumerInfos.iterator();
    }

    public SequenceBarrier getBarrierFor(final EventHandler<T> handler) {
        final ConsumerInfo consumerInfo = getEventProcessorInfo(handler);
        return consumerInfo != null ? consumerInfo.getBarrier() : null;
    }

    private EventProcessorInfo<T> getEventProcessorInfo(final EventHandler<T> handler) {
        return eventProcessorInfoByEventHandler.get(handler);
    }

    private ConsumerInfo getEventProcessorInfo(final Sequence barrierEventProcessor) {
        return eventProcessorInfoBySequence.get(barrierEventProcessor);
    }
}
