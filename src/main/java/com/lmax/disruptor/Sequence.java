/*
 * Copyright 2012 LMAX Ltd.
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

import java.util.concurrent.atomic.LongAdder;

/** 7个 long类型, 进行填充. **/
class LhsPadding { protected long p1, p2, p3, p4, p5, p6, p7;}
class Value extends LhsPadding { protected volatile long value;}

class RhsPadding extends Value { protected long p9, p10, p11, p12, p13, p14, p15; }

/**
 * 可以理解为牛逼点儿的 AtomicLong.
 * 用于跟踪 RingBuffer 和 EventProcessor 的进度的并发序列类。 支持多种并发操作，包括CAS和订单写入。
 * 还尝试通过在 volatile 字段周围添加填充来防止伪共享, 提高性能.
 */
public class Sequence extends RhsPadding {
    static final long INITIAL_VALUE = -1L;
    private static final Unsafe UNSAFE;
    private static final long VALUE_OFFSET;

    static {
        UNSAFE = Util.getUnsafe();
        try {
            //这是一段内存操作，用来获取超类中value字段的内存偏移地址
            VALUE_OFFSET = UNSAFE.objectFieldOffset(Value.class.getDeclaredField("value"));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 初始化方法, 默认 Value = -1.
     */
    public Sequence() { this(INITIAL_VALUE); }
    public Sequence(final long initialValue) { UNSAFE.putOrderedLong(this, VALUE_OFFSET, initialValue); }

    /** 设置 value属性值, 运行期设置. **/
    public void set(final long value) { UNSAFE.putOrderedLong(this, VALUE_OFFSET, value); }

    /** 运行期 value, 设置. **/
    public void setVolatile(final long value) { UNSAFE.putLongVolatile(this, VALUE_OFFSET, value); }

    /** CAS 设置 Value. **/
    public boolean compareAndSet(final long expectedValue, final long newValue) { return UNSAFE.compareAndSwapLong(this, VALUE_OFFSET, expectedValue, newValue); }

    /** 获取 value, 如果是多个 Sequence对象的话, 则 返回 value 最小的那个. **/
    public long get() { return value; }

    /** Value属性 自增, 并 返回 自增后的值. **/
    public long incrementAndGet() { return addAndGet(1L); }

    public long addAndGet(final long increment) {
        long currentValue;
        long newValue;

        do {
            currentValue = get();
            newValue = currentValue + increment;
        } while (!compareAndSet(currentValue, newValue));

        return newValue;
    }

    @Override
    public String toString() { return Long.toString(get()); }

    public static void main(String[] args) {
        Sequence sequence = new Sequence();

        System.out.println(sequence.compareAndSet(-1, 0));

        System.out.println(sequence.get());

        LongAdder longAdder = new LongAdder();

    }
}
