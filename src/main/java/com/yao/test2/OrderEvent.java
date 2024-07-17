/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.test2;

/**
 * @author wangzongyao on 2021/8/26
 */
public class OrderEvent {

    /** 订单的价格. **/
    private long value;

    private OrderEvent(long value){ this.value = value; }

    public static OrderEvent newEmpty(){
        return new OrderEvent(0);
    }

    public long getValue() { return value; }
    public void setValue(long value) { this.value = value; }

}