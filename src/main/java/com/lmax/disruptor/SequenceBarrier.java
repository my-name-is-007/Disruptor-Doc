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
 * SequenceBarrier实例引用被EventProcessor持有,
 * 用于等待并获取可用的消费事件，主要体现在waitFor这个方法
 */
public interface SequenceBarrier {

    /**
     * 等待指定序列可用
     *
     * @param sequence 想要消费的位置
     * @return 可用的序列
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException     if a timeout occurs while waiting for the supplied sequence.
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /** 获取当前可读游标值. **/
    long getCursor();

    /** 当前的alert状态. **/
    boolean isAlerted();

    /** 通知消费者状态变化。当调用EventProcessor#halt()将调用此方法. **/
    void alert();

    /** 清除alert状态. **/
    void clearAlert();

    /** 检查是否发生alert，发生将抛出异常. **/
    void checkAlert() throws AlertException;
}
