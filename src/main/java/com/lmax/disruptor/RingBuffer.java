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


import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.Util;
import sun.misc.Unsafe;

/**
 * 填充辅助类，为解决缓存的伪共享问题，需要对每个缓存行(64Byte)进行填充
 */
abstract class RingBufferPad {
    /** RingBufferFields中的属性被频繁读取，这里的属性是为了避免RingBufferFields遇到伪共享问题. **/
    protected long p1, p2, p3, p4, p5, p6, p7;
}

/**
 * <p>
 *     真正的数据, 存放在 Object[] entries 数组中; 为了性能, 在数组前后各填充了 部分 null 元素:
 *     例如 bufferSize = 32, 前后会各填充 32 个 null 元素, 数组真正的长度就是: 2 * {@link #BUFFER_PAD}
 * </p>
 *
 */
@SuppressWarnings("all")
abstract class RingBufferFields<E> extends RingBufferPad {

    /** 数组前后各填充多少个空元素. **/
    private static final int BUFFER_PAD;

    /**
     * 表示 数组中第一个 "有用的" 元素, 在内存中的地址,
     *
     * 因为我们在上面说了, 为了性能, 在数组首尾都进行了填充, 这里表示的就是在填充之后, 第一个真实元素的内存地址.
     * 如
     *     [null, null, null, null, Value1, Value, Value, Value, null, null, null, null],
     *     REF_ARRAY_BASE 表示的就是 Value1的内存地址
     *
     * 其计算方式: 数组0号元素在内存中的地址 + BUFFER_PAD 个元素的偏移量之和 = 数组中第一个 "有用的" 元素 在内存中的地址
     */
    private static final long REF_ARRAY_BASE;

    /**
     * 该表表示 2的
     * 在计算数组中一个元素的位置时, 可以这样: 数组第一个元素的偏移地址 + i * 每个元素大小, 其中 i 为 数组下标;
     * 数组中每个元素的大小: 如 每个元素大小为8, 此值则为 3; 每个元素大小为4, 此值则为 2;
     *
     * 基于位移计算比乘法运算更高效!
     */
    private static final int REF_ELEMENT_SHIFT;

    /** 使用 UNSAFE 对上面变量进行操作. **/
    private static final Unsafe UNSAFE = Util.getUnsafe();

    static {
        //获取数组中一个元素占用的字节数, 不同JVM实现可能有不同的大小
        final int scale = UNSAFE.arrayIndexScale(Object[].class);

        //当前字节数, 是2的多少次方
        if (4 == scale) {
            REF_ELEMENT_SHIFT = 2;
        } else if (8 == scale) {
            REF_ELEMENT_SHIFT = 3;
        } else {
            throw new IllegalStateException("Unknown pointer size");
        }

        /**
         * 数组前后各填充 32 或者 16 个元素.
         * 为什么 是 128 呢:
         *     为了满足处理器的缓存行预取功能(Adjacent Cache-Line Prefetch)
         */
        BUFFER_PAD = 128 / scale;

        //数组首部元素内存地址 + 首部填充的元素占的内存地址 = 真正有效元素开始的内存地址
        REF_ARRAY_BASE = UNSAFE.arrayBaseOffset(Object[].class) + (BUFFER_PAD << REF_ELEMENT_SHIFT);
    }

    /** bufferSize - 1, 辅助 计算元素位置: 是辅助计算, 不是 & 运算 直接得出. **/
    private final long indexMask;

    /** 真正放数据的数组: 真是长度 = BUFFER_PAD + bufferSize + BUFFER_PAD. **/
    private final Object[] entries;

    /** {@link #entries} 数组中有效数据的个数: 也就是你传进来的长度参数. **/
    protected final int bufferSize;

    /**
     * 生产数据时, 用来控制数据插入的位置, 插入后会唤醒消费者;
     * 单生产者 为 SingleProducer, 多生产者 为 MultiProducerSequencer,
     */
    protected final Sequencer sequencer;

    /**
     * 这里是真正初始化的地方
     * @param eventFactory
     *     创建事件的工厂, 创建RingBuffer时 创建出 存储的元素, 仅此而已. 可以看到, 该参数甚至都没保存到当前的对象中
     *
     * @param sequencer
     *     根据 bufferSize、waitStrategy 两个参数直接new出来的: SingleProducerSequencer/MultiProducerSequencer
     *
     */
    RingBufferFields(EventFactory<E> eventFactory, Sequencer sequencer) {
        this.sequencer = sequencer;
        this.bufferSize = sequencer.getBufferSize();

        //bufferSize 必须是 2 的 N 次方
        if (bufferSize < 1) { throw new IllegalArgumentException("bufferSize must not be less than 1"); }
        if (Integer.bitCount(bufferSize) != 1) { throw new IllegalArgumentException("bufferSize must be a power of 2"); }

        this.indexMask = bufferSize - 1;

        /**
         * 申请的数组 entries 实际大小为 BUFFER_PAD + bufferSize + BUFFER_PAD,
         * BUFFER_PAD 个数组元素占用 128 字节, 也就是说在数组前后各加了 128 字节的填充.
         */
        this.entries = new Object[sequencer.getBufferSize() + 2 * BUFFER_PAD];
        fill(eventFactory);
    }

    /** 对数组中间的 bufferSize 个元素进行初始化. **/
    private void fill(EventFactory<E> eventFactory) {
        for (int i = 0; i < bufferSize; i++) {
            entries[BUFFER_PAD + i] = eventFactory.newInstance();
        }
    }

    /**
     * 获取指定位置的元素:
     *     1. sequence & indexMask: 获取指定数值, 在 环形数组中的下标: 参见 HashMap (length -1) & hash(Key)
     *     2. (sequence & indexMask) << REF_ELEMENT_SHIFT: 当前下标, 相对于数组首个 “有效元素” 的内存偏移量
     *
     * REF_ARRAY_BASE + ((sequence & indexMask) << REF_ELEMENT_SHIFT): 指定元素在内存中的地址 = 数组首元素内存偏移量 + 指定位置元素 相对 首位有效元素的内存偏移量
     *
     * @param sequence 递增的数值, -1 一直向上递增直至溢出(理论上存在溢出的可能),
     */
    protected final E elementAt(long sequence) {
        //通过递增序列号获取与序列号对应的数组元素
        return (E) UNSAFE.getObject(entries, REF_ARRAY_BASE + ((sequence & indexMask) << REF_ELEMENT_SHIFT));
    }
}

/**
 * Ring based store of reusable entries containing the data representing
 * an event being exchanged between event producer and {@link EventProcessor}s.
 *
 * @param <E> implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class RingBuffer<E> extends RingBufferFields<E> implements Cursored, EventSequencer<E>, EventSink<E> {
    public static final long INITIAL_CURSOR_VALUE = Sequence.INITIAL_VALUE;
    protected long p1, p2, p3, p4, p5, p6, p7;

    RingBuffer(EventFactory<E> eventFactory, Sequencer sequencer) { super(eventFactory, sequencer); }

    /**
     *
     * @param producerType
     *     生产者类型, 据此创建 SingleSequenceProducer、MultiSequenceProducer
     * @param factory
     *     事件工厂, 就是生产数据的工厂, 环形数组初始化时会对中间的部分进行初始化, 就是通过他来创建对象的,
     * @param bufferSize
     * @param waitStrategy
     * @param <E>
     * @return
     */
    public static <E> RingBuffer<E> create(ProducerType producerType, EventFactory<E> factory, int bufferSize, WaitStrategy waitStrategy) {
        switch (producerType) {
            case SINGLE:
                return createSingleProducer(factory, bufferSize, waitStrategy);
            case MULTI:
                return createMultiProducer(factory, bufferSize, waitStrategy);
            default:
                throw new IllegalStateException(producerType.toString());
        }
    }

    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory, int bufferSize) {
        return createMultiProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory, int bufferSize, WaitStrategy waitStrategy) {
        MultiProducerSequencer sequencer = new MultiProducerSequencer(bufferSize, waitStrategy);

        return new RingBuffer<E>(factory, sequencer);
    }

    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, int bufferSize) {
        return createSingleProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, int bufferSize, WaitStrategy waitStrategy) {
        //内部设置一下 两个属性,
        SingleProducerSequencer sequencer = new SingleProducerSequencer(bufferSize, waitStrategy);

        //设置 sequencer属性, 并通过 factory 创建初始化 Event对象,
        return new RingBuffer<E>(factory, sequencer);
    }

    /** 获取指定位置的元素. **/
    @Override
    public E get(long sequence) {
        return elementAt(sequence);
    }

    /**
     * 获取 环形缓冲区 的 下一个可写位置 <br/>
     * <pre>
     * long sequence = ringBuffer.next();
     * try {
     *     Event e = ringBuffer.get(sequence);
     *     // Do some work with the event.
     * } finally {
     *     ringBuffer.publish(sequence);
     * }
     * </pre>
     */
    @Override
    public long next() { return sequencer.next(); }

    /** 获取 环形缓冲区 的 下n个可写位置. **/
    @Override
    public long next(int n) { return sequencer.next(n); }

    /** 如果环形缓冲区中没有可用空间，则此方法不会阻塞，而是会抛出异常: {@link InsufficientCapacityException}. **/
    @Override
    public long tryNext() throws InsufficientCapacityException { return sequencer.tryNext(); }

    @Override
    public long tryNext(int n) throws InsufficientCapacityException { return sequencer.tryNext(n); }

    /** 将光标重置为特定值。 这可以在任何时候应用，但值得注意的是，它可能导致数据竞争，只能在受控情况下使用。 例如在初始化期间. **/
    @Deprecated
    public void resetTo(long sequence) {
        sequencer.claim(sequence);
        sequencer.publish(sequence);
    }

    /**
     * Sets the cursor to a specific sequence and returns the preallocated entry that is stored there.  This
     * can cause a data race and should only be done in controlled circumstances, e.g. during initialisation.
     *
     * @param sequence The sequence to claim.
     * @return The preallocated event.
     */
    public E claimAndGetPreallocated(long sequence) {
        sequencer.claim(sequence);
        return get(sequence);
    }

    /**
     * Determines if a particular entry is available.  Note that using this when not within a context that is
     * maintaining a sequence barrier, it is likely that using this to determine if you can read a value is likely
     * to result in a race condition and broken code.
     *
     * @param sequence The sequence to identify the entry.
     * @return If the value can be read or not.
     * @deprecated Please don't use this method.  It probably won't
     * do what you think that it does.
     */
    @Deprecated
    public boolean isPublished(long sequence) {
        return sequencer.isAvailable(sequence);
    }

    /**
     * 将指定对象 添加到 当前 Sequencer 的 门控序列中,
     * 门控是最后一组消费者的Sequence序列, 防止 生产者 生产数据 时 将 消费者 未消费的 覆盖掉.
     */
    public void addGatingSequences(Sequence... gatingSequences) {
        sequencer.addGatingSequences(gatingSequences);
    }

    /**
     * Get the minimum sequence value from all of the gating sequences
     * added to this ringBuffer.
     *
     * @return The minimum gating sequence or the cursor sequence if
     * no sequences have been added.
     */
    public long getMinimumGatingSequence() { return sequencer.getMinimumSequence(); }

    /**
     * Remove the specified sequence from this ringBuffer.
     *
     * @param sequence to be removed.
     * @return <tt>true</tt> if this sequence was found, <tt>false</tt> otherwise.
     */
    public boolean removeGatingSequence(Sequence sequence) {
        return sequencer.removeGatingSequence(sequence);
    }

    /**
     * 多个消费者会组成一个组, 组与组之间需要控制消费的顺序性: 只有上一组消费完了, 当前组才可以消费;
     * 每组的消费者共享一个 SequenceBarrier 对象, 用来控制数据的消费;
     * SequenceBarrier通过持有上一组消费者的Sequence数组, 来控制当前组的消费.
     *
     * @param sequencesToTrack 要监控的消费者关联的Sequence数组
     * @see SequenceBarrier
     */
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack) { return sequencer.newBarrier(sequencesToTrack); }

    /**
     * Creates an event poller for this ring buffer gated on the supplied sequences.
     *
     * @param gatingSequences to be gated on.
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    public EventPoller<E> newPoller(Sequence... gatingSequences) { return sequencer.newPoller(this, gatingSequences); }

    @Override
    public long getCursor() { return sequencer.getCursor(); }

    @Override
    public int getBufferSize() { return bufferSize; }

    @Override
    public boolean hasAvailableCapacity(int requiredCapacity) {
        return sequencer.hasAvailableCapacity(requiredCapacity);
    }

    /** <============================下面基本都是 publishEvent相关API.============================> **/

    @Override
    public void publishEvent(EventTranslator<E> translator) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence);
    }

    @Override
    public <A> void publishEvent(EventTranslatorOneArg<E, A> translator, A arg0) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0);
    }

    @Override
    public <A, B> void publishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1);
    }

    @Override
    public <A, B, C> void publishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1, arg2);
    }

    @Override
    public void publishEvent(EventTranslatorVararg<E> translator, Object... args) {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, args);
    }

    @Override
    public boolean tryPublishEvent(EventTranslator<E> translator) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence);
            return true;
        }
        catch (InsufficientCapacityException e) {
            return false;
        }
    }

    @Override
    public <A> boolean tryPublishEvent(EventTranslatorOneArg<E, A> translator, A arg0) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0);
            return true;
        }
        catch (InsufficientCapacityException e) {
            return false;
        }
    }

    @Override
    public <A, B> boolean tryPublishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1);
            return true;
        }
        catch (InsufficientCapacityException e) {
            return false;
        }
    }

    @Override
    public <A, B, C> boolean tryPublishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1, arg2);
            return true;
        }
        catch (InsufficientCapacityException e) {
            return false;
        }
    }

    @Override
    public boolean tryPublishEvent(EventTranslatorVararg<E> translator, Object... args) {
        try {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, args);
            return true;
        }
        catch (InsufficientCapacityException e) {
            return false;
        }
    }



    @Override
    public void publishEvents(EventTranslator<E>[] translators) {
        publishEvents(translators, 0, translators.length);
    }

    @Override
    public void publishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize) {
        checkBounds(translators, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
    }

    @Override
    public boolean tryPublishEvents(EventTranslator<E>[] translators) {
        return tryPublishEvents(translators, 0, translators.length);
    }

    @Override
    public boolean tryPublishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize) {
        checkBounds(translators, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e) {
            return false;
        }
    }

    @Override
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0) {
        publishEvents(translator, 0, arg0.length, arg0);
    }

    @Override
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0) {
        checkBounds(arg0, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
    }

    @Override
    public <A> boolean tryPublishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0) {
        return tryPublishEvents(translator, 0, arg0.length, arg0);
    }


    @Override
    public <A> boolean tryPublishEvents(EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0) {
        checkBounds(arg0, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see EventSink#publishEvents(EventTranslatorTwoArg, Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, A[], B[])
     */
    @Override
    public <A, B> void publishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1)
    {
        publishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * @see EventSink#publishEvents(EventTranslatorTwoArg, int, int, Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, A[], B[])
     */
    @Override
    public <A, B> void publishEvents(
        EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1)
    {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see EventSink#tryPublishEvents(EventTranslatorTwoArg, Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, A[], B[])
     */
    @Override
    public <A, B> boolean tryPublishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1)
    {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * @see EventSink#tryPublishEvents(EventTranslatorTwoArg, int, int, Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorTwoArg, int, int, A[], B[])
     */
    @Override
    public <A, B> boolean tryPublishEvents(
        EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1)
    {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see EventSink#publishEvents(EventTranslatorThreeArg, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, A[], B[], C[])
     */
    @Override
    public <A, B, C> void publishEvents(EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2)
    {
        publishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * @see EventSink#publishEvents(EventTranslatorThreeArg, int, int, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#publishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, A[], B[], C[])
     */
    @Override
    public <A, B, C> void publishEvents(
        EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2)
    {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * @see EventSink#tryPublishEvents(EventTranslatorThreeArg, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, A[], B[], C[])
     */
    @Override
    public <A, B, C> boolean tryPublishEvents(
        EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2)
    {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * @see EventSink#tryPublishEvents(EventTranslatorThreeArg, int, int, Object[], Object[], Object[])
     * com.lmax.disruptor.EventSink#tryPublishEvents(com.lmax.disruptor.EventTranslatorThreeArg, int, int, A[], B[], C[])
     */
    @Override
    public <A, B, C> boolean tryPublishEvents(
        EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2)
    {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * @see EventSink#publishEvents(EventTranslatorVararg, Object[][])
     */
    @Override
    public void publishEvents(EventTranslatorVararg<E> translator, Object[]... args)
    {
        publishEvents(translator, 0, args.length, args);
    }

    /**
     * @see EventSink#publishEvents(EventTranslatorVararg, int, int, Object[][])
     */
    @Override
    public void publishEvents(EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args)
    {
        checkBounds(batchStartsAt, batchSize, args);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
    }

    /**
     * @see EventSink#tryPublishEvents(EventTranslatorVararg, Object[][])
     */
    @Override
    public boolean tryPublishEvents(EventTranslatorVararg<E> translator, Object[]... args)
    {
        return tryPublishEvents(translator, 0, args.length, args);
    }

    /**
     * @see EventSink#tryPublishEvents(EventTranslatorVararg, int, int, Object[][])
     */
    @Override
    public boolean tryPublishEvents(
        EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args)
    {
        checkBounds(args, batchStartsAt, batchSize);
        try
        {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Publish the specified sequence.  This action marks this particular
     * message as being available to be read.
     *
     * @param sequence the sequence to publish.
     */
    @Override
    public void publish(long sequence) { sequencer.publish(sequence); }

    /**
     * Publish the specified sequences.  This action marks these particular
     * messages as being available to be read.
     *
     * @param lo the lowest sequence number to be published
     * @param hi the highest sequence number to be published
     * @see Sequencer#next(int)
     */
    @Override
    public void publish(long lo, long hi)
    {
        sequencer.publish(lo, hi);
    }

    /**
     * Get the remaining capacity for this ringBuffer.
     *
     * @return The number of slots remaining.
     */
    @Override
    public long remainingCapacity()
    {
        return sequencer.remainingCapacity();
    }

    private void checkBounds(final EventTranslator<E>[] translators, final int batchStartsAt, final int batchSize) {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(translators, batchStartsAt, batchSize);
    }

    private void checkBatchSizing(int batchStartsAt, int batchSize) {
        if (batchStartsAt < 0 || batchSize < 0) {
            throw new IllegalArgumentException("Both batchStartsAt and batchSize must be positive but got: batchStartsAt " + batchStartsAt + " and batchSize " + batchSize);
        }
        else if (batchSize > bufferSize) {
            throw new IllegalArgumentException("The ring buffer cannot accommodate " + batchSize + " it only has space for " + bufferSize + " entities.");
        }
    }

    private <A> void checkBounds(final A[] arg0, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
    }

    private <A, B> void checkBounds(final A[] arg0, final B[] arg1, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
    }

    private <A, B, C> void checkBounds(
        final A[] arg0, final B[] arg1, final C[] arg2, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
        batchOverRuns(arg2, batchStartsAt, batchSize);
    }

    private void checkBounds(final int batchStartsAt, final int batchSize, final Object[][] args)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(args, batchStartsAt, batchSize);
    }

    private <A> void batchOverRuns(final A[] arg0, final int batchStartsAt, final int batchSize)
    {
        if (batchStartsAt + batchSize > arg0.length)
        {
            throw new IllegalArgumentException(
                "A batchSize of: " + batchSize +
                    " with batchStatsAt of: " + batchStartsAt +
                    " will overrun the available number of arguments: " + (arg0.length - batchStartsAt));
        }
    }

    private void translateAndPublish(EventTranslator<E> translator, long sequence) {
        try {
            translator.translateTo(get(sequence), sequence);
        }
        finally {
            sequencer.publish(sequence);
        }
    }

    private <A> void translateAndPublish(EventTranslatorOneArg<E, A> translator, long sequence, A arg0)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A, B> void translateAndPublish(EventTranslatorTwoArg<E, A, B> translator, long sequence, A arg0, B arg1)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0, arg1);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A, B, C> void translateAndPublish(
        EventTranslatorThreeArg<E, A, B, C> translator, long sequence,
        A arg0, B arg1, C arg2)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0, arg1, arg2);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublish(EventTranslatorVararg<E> translator, long sequence, Object... args)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, args);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublishBatch(
        final EventTranslator<E>[] translators, int batchStartsAt,
        final int batchSize, final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                final EventTranslator<E> translator = translators[i];
                translator.translateTo(get(sequence), sequence++);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A> void translateAndPublishBatch(
        final EventTranslatorOneArg<E, A> translator, final A[] arg0,
        int batchStartsAt, final int batchSize, final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, arg0[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B> void translateAndPublishBatch(
        final EventTranslatorTwoArg<E, A, B> translator, final A[] arg0,
        final B[] arg1, int batchStartsAt, int batchSize,
        final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B, C> void translateAndPublishBatch(
        final EventTranslatorThreeArg<E, A, B, C> translator,
        final A[] arg0, final B[] arg1, final C[] arg2, int batchStartsAt,
        final int batchSize, final long finalSequence)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i], arg2[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private void translateAndPublishBatch(
        final EventTranslatorVararg<E> translator, int batchStartsAt,
        final int batchSize, final long finalSequence, final Object[][] args)
    {
        final long initialSequence = finalSequence - (batchSize - 1);
        try
        {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++)
            {
                translator.translateTo(get(sequence), sequence++, args[i]);
            }
        }
        finally
        {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    @Override
    public String toString()
    {
        return "RingBuffer{" +
            "bufferSize=" + bufferSize +
            ", sequencer=" + sequencer +
            "}";
    }
}
