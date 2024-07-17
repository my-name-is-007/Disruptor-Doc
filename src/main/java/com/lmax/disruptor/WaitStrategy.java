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


/**
 * Strategy employed for making {@link EventProcessor}s wait on a cursor {@link Sequence}.
 */
public interface WaitStrategy {

    /**
     * Wait for the given sequence to be available.  It is possible for this method to return a value
     * less than the sequence number supplied depending on the implementation of the WaitStrategy.  A common
     * use for this is to signal a timeout.  Any EventProcessor that is using a WaitStrategy to get notifications
     * about message becoming available should remember to handle this case.  The {@link BatchEventProcessor} explicitly
     * handles this case and will signal a timeout if required.
     *
     * 等待获取想消费的序号.
     *
     * 注意:
     *     1. 返回的序列号, 有如下情况:
     *         1. 比指定要消费的序号小: 例如在 {@link SleepingWaitStrategy}中, 超过一定次数后就退出循环了, 直接返回上组消费的位置
     *         2. 大于等于指定要消费的序号: 这是最常见的情况.
     *                结果可能比指定的值大很多, 例如: 当前消费者可能想要消费 10号位置, 但是上组已经消费到了100号位置, 那么这里返回的就是 100号位置.
     *
     *     2. 阻塞式的实现, 都会判断生产者的发布情况, 然后用锁实现等待唤醒:
     *            首先需要阻塞, 然后经由他人唤醒, 这个唤醒的操作不应由消费者, 而应由生产者来做: 因为这样更合理.
     *        而非阻塞的实现(如休眠、自旋等)则直接判断上一组的消费位点即可
     *
     * @param sequence          等待的可用的序列, 也就是消费者想要消费的数据的下标
     * @param cursor            生产者的使用的游标
     * @param dependentSequence 上一组消费者的Sequence数组
     * @param barrier           消费者组所关联的 SequenceBarrier
     * @return the sequence that is available which may be greater than the requested sequence.
     * @throws AlertException       if the status of the Disruptor has changed.
     * @throws InterruptedException if the thread is interrupted.
     * @throws TimeoutException if a timeout occurs before waiting completes (not used by some strategies)
     */
    long waitFor(long sequence, Sequence cursor, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException, TimeoutException;

    /**
     * Implementations should signal the waiting {@link EventProcessor}s that the cursor has advanced.
     */
    void signalAllWhenBlocking();
}
