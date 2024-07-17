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

import com.lmax.disruptor.util.ThreadHints;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy {
    private final Lock lock = new ReentrantLock();
    private final Condition processorNotifyCondition = lock.newCondition();

    /**
     *
     * @param sequence          当前等待的可用序列, 也就是消费者想要消费的数据的下标
     * @param cursorSequence    生产者 的 Sequence
     * @param dependentSequence 上一组消费者的Sequence数组
     * @param barrier           消费者组所关联的 SequenceBarrier
     * @return 上组消费者 消费最慢 的 位置, 没有上组消费者的话, 就是生产者生产的位置
     * @throws AlertException
     * @throws InterruptedException
     */
    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier) throws AlertException, InterruptedException {
        long availableSequence;

        //生产者下标 小于 消费者想要消费的下标: 说明数据不足, 消费者需要等待
        if (cursorSequence.get() < sequence) {
            lock.lock();
            try {
                while (cursorSequence.get() < sequence) {
                    barrier.checkAlert();
                    processorNotifyCondition.await();
                }
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * 生产者已经生产数据了, 但是还要看上组消费者.
         * 如果 上组 消费速度慢, 消费到的位置 小于 当前消费者想消费的位置: 等等上组消费者.
         *
         * 有个要注意的事情: 当前消费者可能想要消费 10号位置, 但是上组已经消费到了100号位置, 那么这里返回的就是 100号位置.
         */
        while ((availableSequence = dependentSequence.get()) < sequence) {
            barrier.checkAlert();
            ThreadHints.onSpinWait();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking() {
        lock.lock();
        try {
            processorNotifyCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

}
