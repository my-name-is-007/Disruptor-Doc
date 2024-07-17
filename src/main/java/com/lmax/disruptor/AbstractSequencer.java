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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Base class for the various sequencer types (single/multi).  Provides
 * common functionality like the management of gating sequences (add/remove) and
 * ownership of the current cursor.
 *
 * 会作为 RingBuffer 中的属性存在, 控制着 生产者 数据存放的位置和唤醒消费者.
 */
public abstract class AbstractSequencer implements Sequencer {

    /** 生产者游标: 记录当前生产者发布事件的位置. **/
    protected final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    /**
     * 存放所有消费链 最后一个消费者组 的Sequence序列数组: 用于做事件控制, 保证数据发布时不会覆盖未消费完的数据
     * 因为数据的消费, 是按每组的顺序来的, 所以只存储每条链最后一组的 Sequence数组 即可.
     *
     * 为了便于理解, 想象下这么个场景:
     *     我们有3条消费链, 每条链有N个消费组, 每组内有3个消费者(都是HandlerEvent或都是WorkPool), 如下图:
     *
     *
     *                     链条A | 消费组A1 -----> 消费组B1 -----> 消费组C1 ----->
     *         RingBuffer  链条B | 消费组A2 -----> 消费组B2 -----> 消费组C2 ----->
     *                     链条C | 消费组A3 -----> 消费组B3 -----> 消费组C3 ----->
     *
     *         那么该gatingSequences属性, 就只需要存储 消费组 C1、C2、C3 这三个组对应的 Sequence数组即可.
     *         C1的Sequence全部消费完毕, 表示链条A消费完了, 链条B、C同理.
     *         三条都消费完了, 生产者才能占据消费端的位置, 向前推进时才不至于覆盖到未被消费到的数据.
     *
     * 此属性称为: 门控数组, 配合下面的原子引用, 实现原子更新.
     */
    protected volatile Sequence[] gatingSequences = new Sequence[0];
    private static final AtomicReferenceFieldUpdater<AbstractSequencer, Sequence[]> SEQUENCE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractSequencer.class, Sequence[].class, "gatingSequences");

    /** 数组的大小，也就是RingBuffer环的大小. **/
    protected final int bufferSize;

    /** 等待策略. **/
    protected final WaitStrategy waitStrategy;

    public AbstractSequencer(int bufferSize, WaitStrategy waitStrategy) {
        if (bufferSize < 1) { throw new IllegalArgumentException("bufferSize must not be less than 1"); }
        if (Integer.bitCount(bufferSize) != 1) { throw new IllegalArgumentException("bufferSize must be a power of 2"); }

        this.bufferSize = bufferSize;
        this.waitStrategy = waitStrategy;
    }

    /** 返回当前的游标, {@link Sequencer#getCursor()}. **/
    @Override
    public final long getCursor() { return cursor.get(); }

    @Override
    public final int getBufferSize() { return bufferSize; }

    /**
     * 几个消费者构成一个组, 此方法 将 当前组每个消费者 关联的 Sequence组成的数组, 追加到当前门控数组的尾部,
     * 并设置这些Sequence的Value为当前生产者的生产位置: 其实就是设置消费者的消费位置为当前事件发布的位置
     */
    @Override
    public final void addGatingSequences(Sequence... gatingSequences) {
        SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
    }

    /** 从 {@link #gatingSequences} 数组中移除指定元素. **/
    @Override
    public boolean removeGatingSequence(Sequence sequence) {
        return SequenceGroups.removeSequence(this, SEQUENCE_UPDATER, sequence);
    }

    /**
     * 获取 门控数组、cursor 中的最小值,
     * 用于做事件的控制，比如 当前处理速度 最慢的游标
     */
    @Override
    public long getMinimumSequence() {
        return Util.getMinimumSequence(gatingSequences, cursor.get());
    }
    
    /**
     * 创建 当前消费者组 对应的 SequenceBarrier对象: ProcessingSequenceBarrier对象.
     *
     * @param sequencesToTrack 当前消费组要监控的Sequence(也就是上一组消费者的Sequence数组)
     * @see ProcessingSequenceBarrier
     */
    @Override
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack) {
        return new ProcessingSequenceBarrier(this, waitStrategy, cursor, sequencesToTrack);
    }

    /**
     * 为这个序列创建一个事件轮询器，它将使用提供的数据提供者和门控序列
     */
    @Override
    public <T> EventPoller<T> newPoller(DataProvider<T> dataProvider, Sequence... gatingSequences) {
        return EventPoller.newInstance(dataProvider, this, new Sequence(), cursor, gatingSequences);
    }

}