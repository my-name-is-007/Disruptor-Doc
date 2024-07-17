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

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static java.util.Arrays.copyOf;

/**
 * Provides static methods for managing a {@link SequenceGroup} object.
 */
class SequenceGroups {

    static <T> void addSequences(final T holder, final AtomicReferenceFieldUpdater<T, Sequence[]> updater, final Cursored cursor, final Sequence... sequencesToAdd) {
        long cursorSequence;
        Sequence[] updatedSequences;
        Sequence[] currentSequences;

        do {
            currentSequences = updater.get(holder);
            updatedSequences = copyOf(currentSequences, currentSequences.length + sequencesToAdd.length);
            cursorSequence = cursor.getCursor();

            int index = currentSequences.length;
            for (Sequence sequence : sequencesToAdd) {
                sequence.set(cursorSequence);
                updatedSequences[index++] = sequence;
            }
        }
        while (!updater.compareAndSet(holder, currentSequences, updatedSequences));

        cursorSequence = cursor.getCursor();
        for (Sequence sequence : sequencesToAdd) {
            sequence.set(cursorSequence);
        }
    }

    /**
     * 功能: 更新数组, 删除指定的元素.
     *     1. 找到老数组中, 等于待删除元素的数据, 确定待删除的元素个数
     *     2. 创建新数组, 长度 = 老数组 - 待删除元素
     *     3. 老数组保留的元素, 拷贝至新数组
     *     4. CAS, 替换
     *
     * 注: 开始时我还在想, 为什么要考虑数组中, 等于 被删除元素 的元素个数? 因为数组无法动态扩容, 这样是为了计算出新数组的长度, 才能new新数组
     */
    static <T> boolean removeSequence(final T holder, final AtomicReferenceFieldUpdater<T, Sequence[]> sequenceUpdater, final Sequence sequence) {
        int numToRemove;
        Sequence[] oldSequences;
        Sequence[] newSequences;

        do {
            //
            oldSequences = sequenceUpdater.get(holder);

            numToRemove = countMatching(oldSequences, sequence);

            if (0 == numToRemove) {
                break;
            }

            final int oldSize = oldSequences.length;
            newSequences = new Sequence[oldSize - numToRemove];

            for (int i = 0, pos = 0; i < oldSize; i++) {
                final Sequence testSequence = oldSequences[i];
                if (sequence != testSequence) {
                    newSequences[pos++] = testSequence;
                }
            }
        }
        while (!sequenceUpdater.compareAndSet(holder, oldSequences, newSequences));

        return numToRemove != 0;
    }

    /** 指定数组 中 包含多少 指定元素. **/
    private static <T> int countMatching(T[] values, final T toMatch) {
        int numToRemove = 0;
        for (T value : values) {
            if (value == toMatch) {
                numToRemove++;
            }
        }
        return numToRemove;
    }
}
