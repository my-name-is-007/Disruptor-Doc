/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.test2;

import com.lmax.disruptor.RingBuffer;

import java.nio.ByteBuffer;

/**
 * @author wangzongyao on 2021/8/26
 */
public class OrderEventProducer {

    private RingBuffer<OrderEvent> ringBuffer;

    public OrderEventProducer(RingBuffer<OrderEvent> ringBuffer) { this.ringBuffer = ringBuffer; }

    public void sendData(long value) {
        //1 在生产者发送消息的时候, 首先 需要从我们的ringBuffer里面 获取一个可用的序号
        long sequence = ringBuffer.next();  //0
        try {
            //2 根据这个序号, 找到具体的 "OrderEvent" 元素 注意:此时获取的OrderEvent对象是一个没有被赋值 的 "空对象"
            OrderEvent event = ringBuffer.get(sequence);
            //3 进行实际的赋值处理
            event.setValue(value);
        } finally {
            //4 提交发布操作
            ringBuffer.publish(sequence);
        }
    }

}