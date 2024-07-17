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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 这里面竞争的思想很值得我们学习,
 *
 * 和单消费者的 BatchEventProcessor 不同的是：
 *     1. 除了要向 sequenceBarrier 申请可读数据序号之外，同组消费者之间保证互斥访问（通过 workSequence 保证）。
 *     2. BatchEventProcessor 中申请一次可以处理一批数据，而这里一次只能处理一个数据。
 *
 * @param <T> event implementation storing the details for the work to processed.
 */
public final class WorkProcessor<T> implements EventProcessor {

    /** 事件处理器. **/
    private final WorkHandler<? super T> workHandler;

    /** 当前 消费者 消费位置. **/
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    /** 当前组共享的游标. **/
    private final Sequence workSequence;

    /** 当前消费者组屏障. **/
    private final SequenceBarrier sequenceBarrier;

    /** 存储结构. **/
    private final RingBuffer<T> ringBuffer;

    /** 运行 标识. **/
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final TimeoutHandler timeoutHandler;
    private final ExceptionHandler<? super T> exceptionHandler;
    private final EventReleaser eventReleaser = () -> sequence.set(Long.MAX_VALUE);


    /**
     * @param ringBuffer       存储容器.
     * @param sequenceBarrier  当前组共享的消费屏障, 控制组之间消费顺序.
     * @param workHandler      用户声明的处理器.
     * @param workSequence     from which to claim the next event to be worked on.  It should always be initialised
     *                         as {@link Sequencer#INITIAL_CURSOR_VALUE}
     */
    public WorkProcessor(final RingBuffer<T> ringBuffer, final SequenceBarrier sequenceBarrier, final WorkHandler<? super T> workHandler,
                         final ExceptionHandler<? super T> exceptionHandler, final Sequence workSequence) {
        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.workHandler = workHandler;
        this.exceptionHandler = exceptionHandler;
        this.workSequence = workSequence;

        //回调相关, 可忽略
        if (this.workHandler instanceof EventReleaseAware) {
            ((EventReleaseAware) this.workHandler).setEventReleaser(eventReleaser);
        }
        timeoutHandler = (workHandler instanceof TimeoutHandler) ? (TimeoutHandler) workHandler : null;
    }

    @Override
    public Sequence getSequence() { return sequence; }

    /**
     * It is ok to have another thread re-run this method after a halt().
     *
     * @throws IllegalStateException if this processor is already running
     */
    @Override
    public void run() {
        //一个 Processor 只能由一个线程运行
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Thread is already running");
        }

        sequenceBarrier.clearAlert();

        //回调而已,
        notifyStart();

        //表示 是否要去竞争 workSequence
        boolean processedSequence = true;

        //表示我上一次看到的上组消费者中, 消费的最慢的那个, 消费到的位置
        long cachedAvailableSequence = Long.MIN_VALUE;

        long nextSequence = sequence.get();

        T event = null;

        while (true) {
            try {
                if (processedSequence) {
                    processedSequence = false;
                    do {
                        //通过 共享的 workSequence, 得到下一个要消费的数据,
                        nextSequence = workSequence.get() + 1L;

                        //当前消费到的数据位置, 应设置为 workSequence的值.
                        //同一组消费者竞争数据, 而且不共享, 其实是竞争workSequence中的下标, 所以当前消费者消费到的位置, 应该是同一组中workSequence的位置
                        //workSequence 其实代表了当前组多个消费者共同消费的结果, 也是他们共同竞争的点,
                        sequence.set(nextSequence - 1L);
                    }
                    //一组消费者共享同一个 workSequence, 使用 CAS 竞争获取可读数据序号,
                    //上面说了, 看似是在竞争生产者发布的数据, 其实是在竞争 workSequence 的序列号
                    while (!workSequence.compareAndSet(nextSequence - 1L, nextSequence));
                }

                /**
                 * 我上次看到, 上组消费最慢的那个消费者消费到的位置, 都比我现在想消费的位置要靠前, 那我就不用再判断了, 直接消费就好了;
                 * 如果比我想消费的位置小, 那可能是我数据没更新, 太落后了, 那我就再重新获取一次 上组消费者中消费最慢的那个消费者的消费到的位置;
                 *
                 * 注意, 这里很高能, 有个细节要注意下: 这里只消费一个数据, 只消费一个, 只消费一个.
                 * 回顾下 BatchEventProcessor:
                 *     他是消费一批, 可以消费的, 他都会消费, 因为对于数据, 同一组的消费者是共享消费的, 所以消费一批没任何问题,
                 *     而且从名字上也可以看出: Batch, 一批
                 * 但是这里可以看到, 只消费了一个, 只消费 nextSequence处的数据, 为什么会这样呢??????
                 * 其实最根本的原因, 还是消费模式的不同, 这里是竞争的关系, 数据不共享消费的.
                 *
                 * 我们假设这样一种情况:
                 *     一个 WorkProcessor 获取到了10号位的消费权限, 另一个 WorkProcessor获取到了11号位的消费权限,
                 *     同时假设上一组已经消费到了20号位了, 那么按照批处理的逻辑, 10～20之间的数据 可以让第一个WorkProcessor消费, 11～20之间的数据可以让第二个来消费
                 *     那这问题就来了: 两者是有交集的, 那交集的数据岂不是被多个WorkProcessor消费了?
                 * 所以为了避免上述问题, 每次只让他消费一个数据, 也就是你竞争到的那个位置的数据,
                 * 你竞争到了, 相当于提前锁定了, 那位置就属于你了, 你可以消费, 消费完了, 再循环, 再去竞争,
                 *
                 * 其实这种只消费一个的思路, 也是为了完成上面所说的用 CAS来代替锁.
                 *
                 * 其实这里的CAS可以实现范围的锁定: 将 targetValue指定为范围的右区间就好了.
                 * 但是即使你锁定了一个范围, 其他的消费者都没消费到, 你自己独自消费这一堆数据, 那你速度会不会很慢?
                 * 我认为这种竞争的消费模式, 就是为了让消费者快些把数据消费完了就得了.
                 * 而广播模式不同, 他是为了让每个消费者都有机会拿到数据, 更像是让每个消费者都对这个数据做些什么
                 */
                if (cachedAvailableSequence >= nextSequence) {
                    event = ringBuffer.get(nextSequence);
                    workHandler.onEvent(event);
                    processedSequence = true;
                } else {
                    //获取可读数据
                    cachedAvailableSequence = sequenceBarrier.waitFor(nextSequence);
                }
            } catch (final TimeoutException e) {
                notifyTimeout(sequence.get());
            } catch (final AlertException ex) {
                if (!running.get()) {
                    break;
                }
            } catch (final Throwable ex) {
                // handle, mark as processed, unless the exception handler threw an exception
                exceptionHandler.handleEventException(ex, nextSequence, event);
                processedSequence = true;
            }
        }

        notifyShutdown();

        running.set(false);
    }



    /** <===================== 下面回调方法, 不用太细看 =========================>. **/
    /** 终 止. **/
    @Override
    public void halt() {
        running.set(false);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning() { return running.get(); }

    private void notifyTimeout(final long availableSequence) {
        try {
            if (timeoutHandler != null) {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e) {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    private void notifyStart() {
        if (workHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) workHandler).onStart();
            }
            catch (final Throwable ex) {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    private void notifyShutdown() {
        if (workHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) workHandler).onShutdown();
            }
            catch (final Throwable ex) {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}
