/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.test2;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.WorkHandler;

/**
 * @author wangzongyao on 2021/9/10
 */
public class OrderHandler implements EventHandler<OrderEvent>, WorkHandler<OrderEvent> {

    private String name;

    public OrderHandler(String name){
        super();
        this.name = name;
    }

    @Override
    public void onEvent(OrderEvent event) throws Exception {
        System.out.println("线程 ::: " + Thread.currentThread().getName() + ", 独享——消费者 ::: " + name +", 数据 ::: " + event.getValue());
    }

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) throws Exception {
        System.out.println("线程 ::: " + Thread.currentThread().getName() + ", 广播——消费者 ::: " + name +", 数据 ::: " + event.getValue());
    }
}