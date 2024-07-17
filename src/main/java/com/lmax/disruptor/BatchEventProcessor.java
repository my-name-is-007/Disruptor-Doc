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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * 该类用于广播模式.
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T> implements EventProcessor {
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    /** 用户自定义 的 EventHandler 实现, 就你写的内个. **/
    private final EventHandler<? super T> eventHandler;

    /** 当前消费到的位置(消费完的最后一个位置). **/
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    /**
     * 可消费位置的控制:
     *     当前消费组(多个 BatchEventProcessor) 对应的 SequenceBarrier对象.
     *     该消费组共享同一个 SequenceBarrier对象
     * @see ProcessingSequenceBarrier
     */
    private final SequenceBarrier sequenceBarrier;

    /** 数据提供者，默认是RingBuffer，也可替换为自己的数据结构. **/
    private final DataProvider<T> dataProvider;

    private final AtomicInteger runningState = new AtomicInteger(IDLE);

    /** 异常的处理. **/
    private ExceptionHandler<? super T> exceptionHandler;
    private final TimeoutHandler timeoutHandler;
    /** 回调, 主逻辑无关: 每次循环取得一批可用事件后，在实际处理前调用. **/
    private final BatchStartAware batchStartAware;

    public BatchEventProcessor(final DataProvider<T> dataProvider, final SequenceBarrier sequenceBarrier, final EventHandler<? super T> eventHandler) {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler) {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware = (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler = (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    /**
     * 在halt()之后让另一个线程重新运行这个方法是可以的,
     * 主要是调用了 {@link #processEvents()} 方法
     */
    @Override
    public void run() {
        if (runningState.compareAndSet(IDLE, RUNNING)) {
            sequenceBarrier.clearAlert();
            notifyStart();
            try {
                if (runningState.get() == RUNNING) {
                    processEvents();
                }
            }
            finally {
                notifyShutdown();
                runningState.set(IDLE);
            }
        } else {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (runningState.get() == RUNNING) {
                throw new IllegalStateException("Thread is already running");
            } else {
                earlyExit();
            }
        }
    }

    private void processEvents() {
        T event = null;

        //下一个要消费的位置下标
        long nextSequence = sequence.get() + 1L;

        while (true) {
            try {
                /**
                 * 询问 sequenceBarrier(装有前一个组的消费情况), 等待获取指定位置的数据
                 *     nextSequence: 当前消费者想消费的位置;
                 *     availableSequence: 可以消费到的位置;
                 *
                 * 可以消费到的位置  大于等于  想消费的位置: 挨个处理这些数据, 一直消费到 可以消费到的位置
                 * 可以消费到的位置    小于   想消费的位置: 不消费, 直接设置当前消费者的消费位点 为上一组的消费位置
                 *     1. 因为 nextSequence 并没有重新设置, 所以下次循环还是消费 nextSequence 这个位置
                 *     2. 但是我不明白的是, 这时设置 当前消费者消费到的位点, 有意义吗? 这个值大概率是和自己之前的值是相同的
                 *            因为每次都是 nextSequence++
                 */
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                while (nextSequence <= availableSequence) {
                    //根据序号获取数据, 调用 eventHandler 处理数据
                    event = dataProvider.get(nextSequence);
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    nextSequence++;
                }

                //更新最新消费位点
                sequence.set(availableSequence);
            } catch (final TimeoutException e) {
                notifyTimeout(sequence.get());
            } catch (final AlertException ex) {
                if (runningState.get() != RUNNING) {
                    break;
                }
            } catch (final Throwable ex) {
                handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    /** <=============== 下面方法不用看了, 非核心 ================>. **/
    @Override
    public Sequence getSequence() { return sequence; }

    @Override
    public void halt() {
        runningState.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning() { return runningState.get() != IDLE; }

    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler) {
        if (null == exceptionHandler) {
            throw new NullPointerException();
        }
        //直接赋值, 由此可见, 此属性是可被覆盖的,
        this.exceptionHandler = exceptionHandler;
    }

    private void earlyExit() {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence) {
        try {
            if (timeoutHandler != null) {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e) {
            handleEventException(e, availableSequence, null);
        }
    }

    /** 若 {@link #eventHandler} 为 {@link LifecycleAware} 类型, 则 触发其 初始化方法. **/
    private void notifyStart() {
        if (eventHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex) {
                handleOnStartException(ex);
            }
        }
    }

    /** 同 {@link #notifyStart()}. **/
    private void notifyShutdown() {
        if (eventHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex) {
                handleOnShutdownException(ex);
            }
        }
    }

    private void handleOnStartException(final Throwable ex) { getExceptionHandler().handleOnStartException(ex); }
    private void handleEventException(final Throwable ex, final long sequence, final T event) { getExceptionHandler().handleEventException(ex, sequence, event); }
    private void handleOnShutdownException(final Throwable ex) { getExceptionHandler().handleOnShutdownException(ex); }

    /**
     * 返回默认 异常处理器.
     * 这里以及其他地方, 我不是很懂, 为什么要先赋值局部变量, 是为了防止引用泄漏出去被修改引用吗, 例如 指向另一个对象.
     * @return
     */
    private ExceptionHandler<? super T> getExceptionHandler() {
        ExceptionHandler<? super T> handler = exceptionHandler;
        if (handler == null) {
            return ExceptionHandlers.defaultHandler();
        }
        return handler;
    }
}