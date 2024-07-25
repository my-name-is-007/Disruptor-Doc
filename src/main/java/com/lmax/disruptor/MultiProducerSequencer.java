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
import sun.misc.Unsafe;

import java.util.concurrent.locks.LockSupport;

/**
 * 协调器，用于在跟踪依赖Sequence时声明用于访问数据结构的Sequence 。 适用于跨多个发布者线程进行排序。
 * * 关于Sequencer.getCursor()注意事项：使用此排序器，在调用Sequencer.next()后更新光标值，以确定可以读取的最高可用序列，然后应使用Sequencer.getHighestPublishedSequence(long, long) .
 *
 * 建议先看 单生产, 再来阅读此类,
 * @see SingleProducerSequencer
 */
public final class MultiProducerSequencer extends AbstractSequencer {

    private static final Unsafe UNSAFE = Util.getUnsafe();
    private static final long BASE = UNSAFE.arrayBaseOffset(int[].class);
    private static final long SCALE = UNSAFE.arrayIndexScale(int[].class);

    private final Sequence gatingSequenceCache = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    // availableBuffer tracks the state of each ringbuffer slot
    // see below for more details on the approach
    private final int[] availableBuffer;
    private final int indexMask;

    /** 2 的 多少 次方. **/
    private final int indexShift;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public MultiProducerSequencer(int bufferSize, final WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
        availableBuffer = new int[bufferSize];
        indexMask = bufferSize - 1;
        indexShift = Util.log2(bufferSize);
        initialiseAvailableBuffer();
    }

    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity) {
        return hasAvailableCapacity(gatingSequences, requiredCapacity, cursor.get());
    }

    private boolean hasAvailableCapacity(Sequence[] gatingSequences, final int requiredCapacity, long cursorValue) {
        long wrapPoint = (cursorValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = gatingSequenceCache.get();

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > cursorValue) {
            long minSequence = Util.getMinimumSequence(gatingSequences, cursorValue);
            gatingSequenceCache.orderedSet(minSequence);

            if (wrapPoint > minSequence) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void claim(long sequence) {
        cursor.orderedSet(sequence);
    }

    @Override
    public long next() { return next(1); }

    /**
     * 允许一次获取多个写节点.
     * MultiProducerSequencer 使用 CAS 操作来更新写指针位置，
     * 这块是和 SingleProducerSequencer 的主要区别, 单生产者模式由于没有写竞争，所以是直接设置的。
     * 之所以要特意区分单生产者和多生产者是因为，CAS 操作毕竟还是要损耗一些性能的，在没有竞争的情况下，直接赋值效率更高
     *
     */
    @Override
    public long next(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be > 0");
        }

        long current;
        long next;

        do {
            //当前写指针位置: 注意, 这里每次都要重新获取, 而不可以在 do-while外 获取赋值临时变量, 因为是多线程发布数据的,
            current = cursor.get();
            //要写到的位置
            next = current + n;

            //如果会发生环绕, 那么发生的位置
            long wrapPoint = next - bufferSize;
            //是最慢的消费者（读指针）所处的位置: 是一个缓存值, 所以很有可能是会过期的, 需要重新读取,
            long cachedGatingSequence = gatingSequenceCache.get();

            //发生了环绕, 且会覆盖, 其实这里我还是不知道为啥会判断 "cachedGatingSequence > current", 能想到的也就是long溢出,
            if (wrapPoint > cachedGatingSequence || cachedGatingSequence > current) {
                //因为 cachedGatingSequence是缓存的值, 所以可能会过期; 判断会发生环绕, 但实际可能不会, 因此要重新读取
                long gatingSequence = Util.getMinimumSequence(gatingSequences, current);

                //读完了, 还会环绕, 等消费者一会儿,
                if (wrapPoint > gatingSequence) {
                    // TODO, should we spin based on the wait strategy?
                    LockSupport.parkNanos(1);
                    continue;
                }

                //更新下缓存的消费下标,
                gatingSequenceCache.orderedSet(gatingSequence);
            } else if (cursor.compareAndSet(current, next)) {
                //否则使用 CAS 操作更新 cursor
                break;
            }
        } while (true);

        return next;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;

            if (!hasAvailableCapacity(gatingSequences, n, current))
            {
                throw InsufficientCapacityException.INSTANCE;
            }
        }
        while (!cursor.compareAndSet(current, next));

        return next;
    }

    @Override
    public long remainingCapacity() {
        long consumed = Util.getMinimumSequence(gatingSequences, cursor.get());
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }

    private void initialiseAvailableBuffer() {
        for (int i = availableBuffer.length - 1; i != 0; i--) {
            setAvailableBufferValue(i, -1);
        }

        setAvailableBufferValue(0, -1);
    }

    @Override
    public void publish(final long sequence) {
        setAvailable(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void publish(long lo, long hi) {
        for (long l = lo; l <= hi; l++) {
            setAvailable(l);
        }
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * The below methods work on the availableBuffer flag.
     * <p>
     * The prime reason is to avoid a shared sequence object between publisher threads.
     * (Keeping single pointers tracking start and end would require coordination
     * between the threads).
     * <p>
     * --  Firstly we have the constraint that the delta between the cursor and minimum
     * gating sequence will never be larger than the buffer size (the code in
     * next/tryNext in the Sequence takes care of that).
     * -- Given that; take the sequence value and mask off the lower portion of the
     * sequence as the index into the buffer (indexMask). (aka modulo operator)
     * -- The upper portion of the sequence becomes the value to check for availability.
     * ie: it tells us how many times around the ring buffer we've been (aka division)
     * -- Because we can't wrap without the gating sequences moving forward (i.e. the
     * minimum gating sequence is effectively our last available position in the
     * buffer), when we have new data and successfully claimed a slot we can simply
     * write over the top.
     */
    private void setAvailable(final long sequence) {
        setAvailableBufferValue(calculateIndex(sequence), calculateAvailabilityFlag(sequence));
    }

    private void setAvailableBufferValue(int index, int flag) {
        //指定sequence在内存中的地址
        long bufferAddress = (index * SCALE) + BASE;
        UNSAFE.putOrderedInt(availableBuffer, bufferAddress, flag);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence) {
        int index = calculateIndex(sequence);
        int flag = calculateAvailabilityFlag(sequence);
        
        //指定sequence在内存中的地址
        long bufferAddress = (index * SCALE) + BASE;
        return UNSAFE.getIntVolatile(availableBuffer, bufferAddress) == flag;
    }

    /** 计算指定数值的下标, 在环形数组中的位置. **/
    private int calculateIndex(final long sequence) {
        return ((int) sequence) & indexMask;
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence) {
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++) {
            if (!isAvailable(sequence)) {
                return sequence - 1;
            }
        }

        return availableSequence;
    }
    
    /** 等于 sequence * bufferSize. **/
    private int calculateAvailabilityFlag(final long sequence) {
        return (int) (sequence >>> indexShift);
    }

}
