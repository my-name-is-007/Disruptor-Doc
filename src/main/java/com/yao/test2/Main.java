/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.test2;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventHandlerGroup;
import com.lmax.disruptor.dsl.ProducerType;
import com.yao.util.NamedThreadFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 注:
 * @author wangzongyao on 2021/8/26
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {
        //1. 实例化disruptor对象
        Disruptor<OrderEvent> disruptor = new Disruptor<>(new OrderEventFactory(), 8, new NamedThreadFactory("尧大爷线程池"), ProducerType.SINGLE, new BlockingWaitStrategy());

        //2. 消费者 的 监听
        //disruptor.handleEventsWithWorkerPool(new OrderHandler("第一个WorkHandler"), new OrderHandler("第二个WorkHandler"));
        //disruptor.handleEventsWithWorkerPool(new OrderHandler("第一个WorkHandler"), new OrderHandler("第二个WorkHandler"));


//        EventHandlerGroup<OrderEvent> group1 = disruptor.handleEventsWith(new OrderHandler("第一个EventHandler"), new OrderHandler("第二个EventHandler"));
//
//        EventHandlerGroup<OrderEvent> group2 = group1.then(new OrderHandler("第三个EventHandler"), new OrderHandler("第四个EventHandler"));
//
//        EventHandlerGroup<OrderEvent> group3 = group2.handleEventsWithWorkerPool(new OrderHandler("第一个WorkHandler"),
//                        new OrderHandler("第二个WorkHandler"), new OrderHandler("第三个WorkHandler"));
//
//        EventHandlerGroup<OrderEvent> group4 = group3.then(new OrderHandler("第五个EventHandler"), new OrderHandler("第六个EventHandler"));

        //disruptor.handleEventsWith();

        //3. 启动disruptor
        disruptor.start();

        //4. 获取实际存储数据的容器: RingBuffer
        OrderEventProducer producer = new OrderEventProducer(disruptor.getRingBuffer());
        for (long i = 1; i <= 300; i++) {
            producer.sendData(i);
        }
        //disruptor.publishEvent();

        //disruptor.shutdown();
        TimeUnit.SECONDS.sleep(600);
    }
}