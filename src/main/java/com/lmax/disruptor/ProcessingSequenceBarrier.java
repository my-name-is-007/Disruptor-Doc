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
 * 一个 ProcessingSequenceBarrier 实例, 属于一个消费者组(一组 {@link EventProcessor}).
 * 也就是说: 一组 {@link EventProcessor}, 共享同一个 ProcessingSequenceBarrier 对象,
 *
 * 该类为 {@link SequenceBarrier} 唯一实现,
 */
final class ProcessingSequenceBarrier implements SequenceBarrier {

    /**
     * 要监控的Sequence数组: 上一个消费者组的Sequence数组 或者 生产者的Sequence.
     *     上一个消费者组的Sequence数组    为空时:  该值为 生产者的Sequence: 因为前面没有消费者组, 当前组为第一个, 所以监控着生产者的 Sequence即可;
     *     上一个消费者组的Sequence数组  不为空时:  将上一消费者组的Sequence数组, 封装为 FixedSequenceGroup
     *
     * 消费时可以有多个组, 只有上一组消费完毕, 当前组才可以消费.
     */
    private final Sequence dependentSequence;

    /** {@link AbstractSequencer#cursor} 属性值, 记录 当前 生产者 发布 的 最新位置. **/
    private final Sequence cursorSequence;

    /**
     * 生产者 实例, 一个 Disruptor范围内唯一, 也是 RingBuffer 中的 cursor 实例属性的值;
     * 一般是 {@link MultiProducerSequencer} 或 {@link SingleProducerSequencer}.
     */
    private final Sequencer sequencer;

    /** 当触发 halt 时，将标记alerted为true. **/
    private volatile boolean alerted = false;

    /** 等待可用消费时，指定的等待策略: 和 AbstractSequencer 共享 此属性指向的对象. **/
    private final WaitStrategy waitStrategy;

    /**
     * @param sequencer 当前 Disruptor范围内的生产者,
     * @param waitStrategy 等待策略, AbstractSequencer 中的 属性值, 控制生产者无法发布等待消费时的等待策略, 此处传了过来, 用来控制 消费者无法消费等待发布(和等待上一组消费)时的策略
     * @param cursorSequence Sequence 实例, {@link AbstractSequencer#cursor} 属性, 记录发布的位置
     * @param dependentSequences 要监控的Sequence数组, 一般是上一组消费者的Sequence数组
     */
    ProcessingSequenceBarrier(final Sequencer sequencer, final WaitStrategy waitStrategy, final Sequence cursorSequence, final Sequence[] dependentSequences) {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;

        /**
         * 上组消费者的Sequence数组长度:
         *     0: 无元素, 说明当前消费者组为第一组, 所以只需要看他妈的生产者的脸色即可;
         *     非0: 当前消费者组不是第一组, 他要等上一组消费完了才能消费, 不看生产者脸色, 看他妈他上家的脸色;
         */
        if (0 == dependentSequences.length) {
            dependentSequence = cursorSequence;
        } else {
            //FixedSequenceGroup: 其实就是一个数组包了一层,
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    /**
     * 等待指定序列可用
     * @param sequence 想要消费的位置, 也就是消费者想要消费的数据的下标, 一般为 当前消费者消费到的序号 + 1
     * @return
     */
    @Override
    public long waitFor(final long sequence) throws AlertException, InterruptedException, TimeoutException {
        //检查是否停止服务
        checkAlert();

        /**
         * 获取当前组可用的消费位置: 直接去看注释吧, 老清楚了.
         *
         * waitStrategy: 默认采用 BlockingWaitStrategy,
         */
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);

        /**
         * 可用的序列号 是有可能比 想消费的序列号 小的
         * @see WaitStrategy#waitFor(long, Sequence, Sequence, SequenceBarrier)
         */
        if (availableSequence < sequence) {
            return availableSequence;
        }

        /**
         * 正常情况: 返回的序列号 大于等于 想消费位置的值大;
         *
         * 其实可以将 想消费的序列号、实际可用的序列号 看为一个区间:
         *     最小值: 想要消费的序列号
         *     最大值: 实际可用的序列号
         */
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    /** 当前消费者组 的 可读游标. **/
    @Override
    public long getCursor() { return dependentSequence.get(); }

    @Override
    public boolean isAlerted() { return alerted; }

    @Override
    public void alert() {
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert() { alerted = false; }

    @Override
    public void checkAlert() throws AlertException {
        if (alerted) {
            throw AlertException.INSTANCE;
        }
    }
}