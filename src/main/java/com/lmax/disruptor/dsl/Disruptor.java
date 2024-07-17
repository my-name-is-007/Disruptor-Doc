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
import com.lmax.disruptor.util.Util;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>A DSL-style API for setting up the disruptor pattern around a ring buffer
 * (aka the Builder pattern).</p>
 *
 * <p>A simple example of setting up the disruptor with two event handlers that
 * must process events in order:</p>
 * <pre>
 * <code>Disruptor&lt;MyEvent&gt; disruptor = new Disruptor&lt;MyEvent&gt;(MyEvent.FACTORY, 32, Executors.newCachedThreadPool());
 * EventHandler&lt;MyEvent&gt; handler1 = new EventHandler&lt;MyEvent&gt;() { ... };
 * EventHandler&lt;MyEvent&gt; handler2 = new EventHandler&lt;MyEvent&gt;() { ... };
 * disruptor.handleEventsWith(handler1);
 * disruptor.after(handler1).handleEventsWith(handler2);
 *
 * RingBuffer ringBuffer = disruptor.start();</code>
 * </pre>
 *
 * @param <T> the type of event used.
 */
public class Disruptor<T> {
    private final RingBuffer<T> ringBuffer;
    private final Executor executor;
    private final ConsumerRepository<T> consumerRepository = new ConsumerRepository<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExceptionHandler<? super T> exceptionHandler = new ExceptionHandlerWrapper<>();

    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final ThreadFactory threadFactory) {
        this(RingBuffer.createMultiProducer(eventFactory, ringBufferSize), new BasicExecutor(threadFactory));
    }

    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final ThreadFactory threadFactory, final ProducerType producerType, final WaitStrategy waitStrategy) {
        this(RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy), new BasicExecutor(threadFactory));
    }

    private Disruptor(final RingBuffer<T> ringBuffer, final Executor executor) {
        this.ringBuffer = ringBuffer;
        this.executor = executor;
    }

    /**
     * Disruptor 直接创建一个新的消费者组, 当前组为第一组, 故而会 "new Sequence[0]";
     * 当 通过 {@link EventHandlerGroup#then(EventHandler[])}时, 是在指定 消费者组后再追加一组,
     *     传入的 Sequence数组 就不是 "new Sequence[0]"了, 而是 上一组消费者的 Sequence数组, 即: {@link EventHandlerGroup#sequences}
     *
     * EventHandlerGroup 类也可以创建 新的消费者组，但是是在当前组的基础上，进行追加新的组或者直接不新建组，就将消费者加到组的尾部
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers) {
        return createEventProcessors(new Sequence[0], handlers);
    }

    /**
     * 广播模式.
     * @param barrierSequences 上一组 {@link EventProcessor} 的 Sequence组成的数组
     * @param eventHandlers 当前的事件处理器中指定的业务逻辑
     * @return
     */
    EventHandlerGroup<T> createEventProcessors(final Sequence[] barrierSequences, final EventHandler<? super T>[] eventHandlers) {
        checkNotStarted();

        /**
         * EventHandler 会用来创建 BatchEventProcessor, 每个 BatchEventProcessor 会关联一个 Sequence,
         * 此变量就是 用来盛放 每个 BatchEventProcessor 关联的 Sequence 属性的;
         */
        final Sequence[] processorSequences = new Sequence[eventHandlers.length];

        /**
         * 同一组(或者叫同一环节)的多个 BatchEventProcessor, 共用一个 SequenceBarrier;
         * BatchEventProcessor 可以根据 SequenceBarrier 获取下一个可消费数据,
         *
         * SequenceBarrier 同样要知道上一组消费者信息, 所以需要 barrierSequences 参数,
         */
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        /**
         * 循环 用户的 EventHandler实例：
         *     1. 为每个实例，创建对应 BatchEventProcessor 对象
         *     2. 为每个 BatchEventProcessor，设置异常处理器
         *     3. 设置 processorSequences数组，用于后续更新操作
         *
         * 这种for循环的写法儿, 是将一个length提前保存起来, 后续遍历时, 无序每次都再调用一个 数组.length ?
         */
        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++) {
            //根据EventHandler, 创建 BatchEventProcessor: ringBuffer、当前消费组共享的SequenceBarrier、用户自定义的事件处理器
            final EventHandler<? super T> eventHandler = eventHandlers[i];
            final BatchEventProcessor<T> batchEventProcessor = new BatchEventProcessor<>(ringBuffer, barrier, eventHandler);

            //异常处理器
            if (exceptionHandler != null) {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }

            //？
            consumerRepository.add(batchEventProcessor, eventHandler, barrier);

            //存储 BatchEventProcessor 关联 的 Sequence 属性 至最上面的数组,
            processorSequences[i] = batchEventProcessor.getSequence();
        }

        /**
         * 生产者会存储最后一组消费者关联的Sequence数组, 我们这里称之为 A
         * 因为这里已经产生了新的消费者组了, 所以需要:
         *     将当前消费者组关联的Sequence数组(processorSequences变量), 添加到A的尾部
         *     将原有上一消费者组关联的Sequence数组从A中移除
         */
        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

    /**
     *
     * @param barrierSequences
     *     上一组 EventProcessor 关联的 Sequence数组,
     * @param processorSequences
     *     当前组 EventProcessor 关联的 Sequence数组
     */
    private void updateGatingSequencesForNextInChain(final Sequence[] barrierSequences, final Sequence[] processorSequences) {
        if(processorSequences.length <= 0){
            return ;
        }

        //将当前组每个EventProcessor的Sequence, 添加到 Sequencer尾部,
        ringBuffer.addGatingSequences(processorSequences);

        //将上一组每个EventProcessor的Sequence, 从 Sequencer中移除
        for (final Sequence barrierSequence : barrierSequences){
            ringBuffer.removeGatingSequence(barrierSequence);
        }
        //标识上一组, 不是消费链条的最后一环,
        consumerRepository.unMarkEventProcessorsAsEndOfChain(barrierSequences);
    }

    /**
     * 启动事件处理器并返回完全配置的环形缓冲区。
     * 设置环形缓冲区是为了防止覆盖任何尚未由最慢的事件处理器处理的条目。
     *
     * 注意: 此方法一般在所有事件处理器添加后调用, 而且只能调用一次
     */
    public RingBuffer<T> start() {
        checkOnlyStartedOnce();
        for (final ConsumerInfo consumerInfo : consumerRepository) {
            consumerInfo.start(executor);
        }

        return ringBuffer;
    }

    /**
     * 设置自定义事件处理器来处理来自环形缓冲区的事件。 当start()被调用时，Disruptor 将自动启动这些处理器。
     * 此方法可用作链的开始。 例如，如果处理程序A必须在处理程序B之前处理事件：
     * dw.handleEventsWith(A).then(B);
     * 由于这是链的开始，处理器工厂将始终传递一个空的Sequence数组，因此在这种情况下不需要工厂。
     * 提供此方法是为了与EventHandlerGroup.handleEventsWith(EventProcessorFactory...)和EventHandlerGroup.then(EventProcessorFactory...)保持一致，它们确实提供了障碍序列。
     * 此调用是附加的，但一般只应在设置 Disruptor 实例时调用一次
     *
     * @param eventProcessorFactories 用于创建将处理事件的事件处理器的事件处理器工厂
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories) {
        final Sequence[] barrierSequences = new Sequence[0];
        return createEventProcessors(barrierSequences, eventProcessorFactories);
    }

    public EventHandlerGroup<T> handleEventsWith(final EventProcessor... processors) {
        for (final EventProcessor processor : processors) {
            consumerRepository.add(processor);
        }

        final Sequence[] sequences = new Sequence[processors.length];
        for (int i = 0; i < processors.length; i++) {
            sequences[i] = processors[i].getSequence();
        }

        ringBuffer.addGatingSequences(sequences);

        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }

    /** Distruptor直接创建, 所以 上面无消费者组, 所以 传 "new Sequence[0]". **/
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWithWorkerPool(final WorkHandler<T>... workHandlers) {
        return createWorkerPool(new Sequence[0], workHandlers);
    }

    EventHandlerGroup<T> createWorkerPool(final Sequence[] barrierSequences, final WorkHandler<? super T>[] workHandlers) {
        //当前消费者组 的 SequenceBarrier, 同一组的多个 EventProcessor共享同一个 SequenceBarrier,
        final SequenceBarrier sequenceBarrier = ringBuffer.newBarrier(barrierSequences);

        //多个 WorkHandler组成的Pool,
        final WorkerPool<T> workerPool = new WorkerPool<>(ringBuffer, sequenceBarrier, exceptionHandler, workHandlers);

        consumerRepository.add(workerPool, sequenceBarrier);

        //得到每个 WorkerHandler 关联的 Sequence, 其实逻辑基本和 EventHandler 差不多的,
        final Sequence[] workerSequences = workerPool.getWorkerSequences();

        //还是更新,
        updateGatingSequencesForNextInChain(barrierSequences, workerSequences);

        return new EventHandlerGroup<>(this, consumerRepository, workerSequences);
    }

    public void handleExceptionsWith(final ExceptionHandler<? super T> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    public void setDefaultExceptionHandler(final ExceptionHandler<? super T> exceptionHandler) {
        checkNotStarted();
        if (!(this.exceptionHandler instanceof ExceptionHandlerWrapper)) {
            throw new IllegalStateException("setDefaultExceptionHandler can not be used after handleExceptionsWith");
        }
        ((ExceptionHandlerWrapper<T>)this.exceptionHandler).switchTo(exceptionHandler);
    }

    /**
     * Override the default exception handler for a specific handler.
     * <pre>disruptorWizard.handleExceptionsIn(eventHandler).with(exceptionHandler);</pre>
     *
     * @param eventHandler the event handler to set a different exception handler for.
     * @return an ExceptionHandlerSetting dsl object - intended to be used by chaining the with method call.
     */
    public ExceptionHandlerSetting<T> handleExceptionsFor(final EventHandler<T> eventHandler) {
        return new ExceptionHandlerSetting<>(eventHandler, consumerRepository);
    }

    /**
     * <p>Create a group of event handlers to be used as a dependency.
     * For example if the handler <code>A</code> must process events before handler <code>B</code>:</p>
     *
     * <pre><code>dw.after(A).handleEventsWith(B);</code></pre>
     *
     * @param handlers the event handlers, previously set up with {@link #handleEventsWith(EventHandler[])},
     *                 that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a dependency barrier over the specified event handlers.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final EventHandlerGroup<T> after(final EventHandler<T>... handlers) {
        final Sequence[] sequences = new Sequence[handlers.length];
        for (int i = 0, handlersLength = handlers.length; i < handlersLength; i++) {
            sequences[i] = consumerRepository.getSequenceFor(handlers[i]);
        }

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }

    /**
     * Create a group of event processors to be used as a dependency.
     *
     * @param processors the event processors, previously set up with {@link #handleEventsWith(EventProcessor...)},
     *                   that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a {@link SequenceBarrier} over the specified event processors.
     * @see #after(EventHandler[])
     */
    public EventHandlerGroup<T> after(final EventProcessor... processors) {
        for (final EventProcessor processor : processors)
        {
            consumerRepository.add(processor);
        }

        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }

    /**
     * Calls {@link EventProcessor#halt()} on all of the event processors created via this disruptor.
     */
    public void halt()
    {
        for (final ConsumerInfo consumerInfo : consumerRepository)
        {
            consumerInfo.halt();
        }
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.  It is critical that publishing to the ring buffer has stopped
     * before calling this method, otherwise it may never return.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     */
    public void shutdown() {
        try {
            shutdown(-1, TimeUnit.MILLISECONDS);
        }
        catch (final TimeoutException e) {
            exceptionHandler.handleOnShutdownException(e);
        }
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     *
     * @param timeout  the amount of time to wait for all events to be processed. <code>-1</code> will give an infinite timeout
     * @param timeUnit the unit the timeOut is specified in
     * @throws TimeoutException if a timeout occurs before shutdown completes.
     */
    public void shutdown(final long timeout, final TimeUnit timeUnit) throws TimeoutException
    {
        final long timeOutAt = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        while (hasBacklog())
        {
            if (timeout >= 0 && System.currentTimeMillis() > timeOutAt)
            {
                throw TimeoutException.INSTANCE;
            }
            // Busy spin
        }
        halt();
    }

    /**
     * The {@link RingBuffer} used by this Disruptor.  This is useful for creating custom
     * event processors if the behaviour of {@link BatchEventProcessor} is not suitable.
     *
     * @return the ring buffer used by this Disruptor.
     */
    public RingBuffer<T> getRingBuffer()
    {
        return ringBuffer;
    }

    /**
     * Get the value of the cursor indicating the published sequence.
     *
     * @return value of the cursor for events that have been published.
     */
    public long getCursor() {
        return ringBuffer.getCursor();
    }

    /**
     * Get the event for a given sequence in the RingBuffer.
     *
     * @param sequence for the event.
     * @return event for the sequence.
     * @see RingBuffer#get(long)
     */
    public T get(final long sequence)
    {
        return ringBuffer.get(sequence);
    }

    /**
     * Confirms if all messages have been consumed by all event processors
     */
    private boolean hasBacklog()
    {
        final long cursor = ringBuffer.getCursor();
        for (final Sequence consumer : consumerRepository.getLastSequenceInChain(false))
        {
            if (cursor > consumer.get())
            {
                return true;
            }
        }
        return false;
    }

    EventHandlerGroup<T> createEventProcessors(
        final Sequence[] barrierSequences, final EventProcessorFactory<T>[] processorFactories)
    {
        final EventProcessor[] eventProcessors = new EventProcessor[processorFactories.length];
        for (int i = 0; i < processorFactories.length; i++)
        {
            eventProcessors[i] = processorFactories[i].createEventProcessor(ringBuffer, barrierSequences);
        }

        return handleEventsWith(eventProcessors);
    }

    private void checkNotStarted() {
        if (started.get()) {
            throw new IllegalStateException("All event handlers must be added before calling starts.");
        }
    }

    private void checkOnlyStartedOnce()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("Disruptor.start() must only be called once.");
        }
    }

}
