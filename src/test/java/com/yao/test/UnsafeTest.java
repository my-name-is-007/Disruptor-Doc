/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.test;

import com.lmax.disruptor.util.Util;
import org.junit.Test;
import sun.misc.Unsafe;

/**
 * @author wangzongyao on 2021/9/17
 */
public class UnsafeTest {

    private static final Unsafe UNSAFE = Util.getUnsafe();

    /**
     * 数组中每个元素占用的大小
     */
    @Test
    public void arrayIndexScaleTest(){
        System.out.println("long    数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(long[].class));
        System.out.println("int     数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(int[].class));

        System.out.println("double  数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(double[].class));
        System.out.println("float    数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(float[].class));

        System.out.println("short   数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(short[].class));
        System.out.println("byte    数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(byte[].class));

        System.out.println("char    数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(char[].class));
        System.out.println("boolean 数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(boolean[].class));

        System.out.println("String  数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(String[].class));

        System.out.println("Object  数组 每个元素占用大小 ::: " + UNSAFE.arrayIndexScale(Object[].class));
    }

    /**
     * 数组第一个元素的偏移地址,
     */
    @Test
    public void arrayBaseOffsetTest(){
        System.out.println("long    数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(long[].class));
        System.out.println("int     数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(int[].class));

        System.out.println("double  数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(double[].class));
        System.out.println("float    数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(float[].class));

        System.out.println("short   数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(short[].class));
        System.out.println("byte    数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(byte[].class));

        System.out.println("char    数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(char[].class));
        System.out.println("boolean 数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(boolean[].class));

        System.out.println("String  数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(String[].class));

        System.out.println("Object  数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(Object[].class));
        System.out.println("Object  数组 首元素偏移地址 ::: " + UNSAFE.arrayBaseOffset(Long[].class));
    }




}