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

import java.util.concurrent.locks.LockSupport;

abstract class SingleProducerSequencerPad extends AbstractSequencer {
    protected long p1, p2, p3, p4, p5, p6, p7;
    SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy) { super(bufferSize, waitStrategy); }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad {

    SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy) { super(bufferSize, waitStrategy); }

    /** 最后一次预定到的位置: 该值比游标靠前(游标一般在发布时才会设置, 当然其他情况可能也会设置), 初始值 为 -1. **/
    long nextValue = Sequence.INITIAL_VALUE;

    /** 在 所有最后一组 的 所有消费者中, 消费最慢的那个消费者 的消费位点. **/
    long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * 协调器，用于在跟踪依赖Sequence时声明用于访问数据结构的Sequence 。 多线程使用不安全，因为它没有实现任何障碍。
 * * 关于Sequencer.getCursor()注意事项：使用此定序器，在调用Sequencer.publish(long)后会更新光标值
 */
public final class SingleProducerSequencer extends SingleProducerSequencerFields {
    protected long p1, p2, p3, p4, p5, p6, p7;

    public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy) {
        super(bufferSize, waitStrategy);
    }

    @Override
    public long next() { return next(1); }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n) {
        if (n < 1) { throw new IllegalArgumentException("n must be > 0"); }

        //当前生产者已经发布的最后一个位置,
        long nextValue = this.nextValue;

        //当前数据发布, 要发布到的最后一个位置(有可能发布多个数据)
        long nextSequence = nextValue + n;

        //可能发生绕环的点，本次申请值 - 环形一圈长度
        long wrapPoint = nextSequence - bufferSize;

        //数值最小的序列值，理解为最慢消费者
        long cachedGatingSequence = this.cachedValue;

        /**
         * wrapPoint 大于 cachedGatingSequence: 将发生绕环行为, 此时 生产者超一圈从后方追上了消费者，会造成生产者覆盖未消费的情况;
         * 至于 cachedGatingSequence > nextValue: 我怀疑是long发生了溢出, 因为下标是一直递增的, 我实在找不到消费者下标大于生产者的情况, 除非生产者先溢出.
         * 看了些文章, 说这里是溢出的问题
         */
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) {
            /**
             * 这里在使用错误时, 可能会发生重复消费的问题, 可以这样触发:
             *     1. 生产者获取 一个next: 只获取, 不发布. nextValue属性 就是最新的位置, 假设此时未发生回环, 则游标不变, 还是之前设置的
             *     2. 生产者再获取一个next: 局部nextValue变量, 就是上次获取但未发布的位置, 假设此时发生回环, 则会执行 此处的 setVolatile方法,
             *            那么游标的值就会设置未上次 只获取未发布的位置, 但是那个位置还没有发布任何值.
             *            但是已经对消费者可见了, 那么消费者消费到的可能就是上次消费的值, 也有可能这个位置根本就没设置过任何值, 那么拿到的就是初始化Disruptor时设置的初始值,
             *
             * 但是, 也说了, 这是使用错误的方式: 必须是预定位置, 然后设置事件数据, 然后发布. 不过这里可以是一个使用的细节问题,
             */
            cursor.setVolatile(nextValue);

            /**
             * 只有当消费者消费，向前移动后，才能跳出循环
             * 每次重新获取消费者序列最小值进行轮询判断
             */
            long minSequence;

            //当前发布的位置, 发生了环绕, 而且还比消费者消费的位置大, 或者比上次发布的位置大(说明上次发布就绕了一圈了, 但消费者还未消费到), 说明 此次发布会造成数据覆盖;
            //要么是覆盖 消费快的已经消费的但消费慢的还未来得及消费的那部分; 要么是覆盖都还没消费到的数据,
            //虽然是环形数组, 但下标是一直向前递增的, 所以我怀疑这里要与 nextValue一起比较得到最小值, 可能是因为会溢出,
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue))) {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            //当消费者消费后，更新缓存的最小序号
            this.cachedValue = minSequence;
        }

        //本次发布到的位置
        this.nextValue = nextSequence;

        return nextSequence;
    }

    /**
     * 发布 一个事件 到 指定位置.
     * 
     * 事件发布, 其实是基于预定/声明机制:
     *     1. 先预定某个位置, 我后面要在这里发布事件
     *     2. 等我将这块地方的事件设置好了之后, 再声明这件事情, 告诉大家: 这里可以使用了
     */
    @Override
    public void publish(long sequence) {
        cursor.set(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * 批量发布多个事件, 到指定位置: 将游标设置到最高位置即可.
     *
     * @param lo 本次可用事件的最小下标
     * @param hi 本次可用事件的最大下标
     */
    @Override
    public void publish(long lo, long hi) {
        publish(hi);
    }

    @Override
    public long tryNext() throws InsufficientCapacityException {
        return tryNext(1);
    }

    /**
     * 其实我之前还在想, 即然是 tryNext, 就类似于 可重入锁 的 tryLock, 不行的话应该给个通过, 而不是直接返回结果. 或者返回的结果是 包装类型, 通过null表示尝试失败.
     * 看到异常明白了, 是通过异常的形式来通知调用方, try成功还是失败. 看到异常通知, 想到了 Sentinel, 限流失败时有一种方式就是抛出异常.
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException {
        if (n < 1) { throw new IllegalArgumentException("n must be > 0"); }

        if (!hasAvailableCapacity(n, true)) { throw InsufficientCapacityException.INSTANCE; }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /** 还剩多少可写的空间. **/
    @Override
    public long remainingCapacity() {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /** 设置 {@link #nextValue} 为 指定值, 仅在将环形缓冲区初始化为特定值时使用. **/
    @Override
    public void claim(long sequence) {
        this.nextValue = sequence;
    }

    /** 指定序列 是否 可供消费. **/
    @Override
    public boolean isAvailable(long sequence) { return sequence <= cursor.get(); }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence) { return availableSequence; }

    @Override
    public boolean hasAvailableCapacity(int requiredCapacity) { return hasAvailableCapacity(requiredCapacity, false); }

    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore) {
        long nextValue = this.nextValue;

        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) {
            if (doStore) {
                //StoreLoad fence
                cursor.setVolatile(nextValue);
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            if (wrapPoint > minSequence) {
                return false;
            }
        }

        return true;
    }

}
