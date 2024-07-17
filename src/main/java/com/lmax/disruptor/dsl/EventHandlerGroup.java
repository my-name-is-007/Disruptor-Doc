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

import java.util.Arrays;

/**
 * 多个消费者({@link EventProcessor})构成的组:
 *     多个 {@link BatchEventProcessor},
 *         或者
 *     多个 {@link WorkProcessor}
 *
 * @param <T> the type of entry used by the event processors.
 */
public class EventHandlerGroup<T> {

    private final Disruptor<T> disruptor;

    /** 一个Disruptor范围, 共享一个次对象. **/
    private final ConsumerRepository<T> consumerRepository;

    /** 当前组, 每个{@link EventProcessor}对应的 Sequence, 构成的数组. **/
    private final Sequence[] sequences;

    EventHandlerGroup(final Disruptor<T> disruptor, final ConsumerRepository<T> consumerRepository, final Sequence[] sequences) {
        this.disruptor = disruptor;
        this.consumerRepository = consumerRepository;
        //将sequences数组的内容, 重新拷贝一份儿到新数组, this.sequences 指向到新数组
        this.sequences = Arrays.copyOf(sequences, sequences.length);
    }

    @SafeVarargs
    public final EventHandlerGroup<T> then(final EventHandler<? super T>... handlers) {
        return handleEventsWith(handlers);
    }

    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers) {
        return disruptor.createEventProcessors(sequences, handlers);
    }

    public EventHandlerGroup<T> and(final EventHandlerGroup<T> otherHandlerGroup) {
        final Sequence[] combinedSequences = new Sequence[this.sequences.length + otherHandlerGroup.sequences.length];
        System.arraycopy(this.sequences, 0, combinedSequences, 0, this.sequences.length);
        System.arraycopy(otherHandlerGroup.sequences, 0, combinedSequences, this.sequences.length, otherHandlerGroup.sequences.length);
        return new EventHandlerGroup<>(disruptor, consumerRepository, combinedSequences);
    }

    public EventHandlerGroup<T> and(final EventProcessor... processors) {
        Sequence[] combinedSequences = new Sequence[sequences.length + processors.length];

        for (int i = 0; i < processors.length; i++) {
            consumerRepository.add(processors[i]);
            combinedSequences[i] = processors[i].getSequence();
        }
        System.arraycopy(sequences, 0, combinedSequences, processors.length, sequences.length);

        return new EventHandlerGroup<>(disruptor, consumerRepository, combinedSequences);
    }

    @SafeVarargs
    public final EventHandlerGroup<T> then(final EventProcessorFactory<T>... eventProcessorFactories) {
        return handleEventsWith(eventProcessorFactories);
    }

    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories) {
        return disruptor.createEventProcessors(sequences, eventProcessorFactories);
    }

    @SafeVarargs
    public final EventHandlerGroup<T> thenHandleEventsWithWorkerPool(final WorkHandler<? super T>... handlers) {
        return handleEventsWithWorkerPool(handlers);
    }

    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWithWorkerPool(final WorkHandler<? super T>... handlers) {
        return disruptor.createWorkerPool(sequences, handlers);
    }

    public SequenceBarrier asSequenceBarrier() {
        return disruptor.getRingBuffer().newBarrier(sequences);
    }
}
