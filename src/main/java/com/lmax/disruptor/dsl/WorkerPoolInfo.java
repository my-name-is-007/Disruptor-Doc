package com.lmax.disruptor.dsl;

import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.WorkerPool;

import java.util.concurrent.Executor;

/**
 * WorkerPool 信息
 * @param <T>
 */
class WorkerPoolInfo<T> implements ConsumerInfo {

    /** WorkPool. **/
    private final WorkerPool<T> workerPool;

    /** WorkPool内, 一组消费者关联 SequenceBarrier. **/
    private final SequenceBarrier sequenceBarrier;

    private boolean endOfChain = true;

    WorkerPoolInfo(final WorkerPool<T> workerPool, final SequenceBarrier sequenceBarrier) {
        this.workerPool = workerPool;
        this.sequenceBarrier = sequenceBarrier;
    }

    /** 这里会返回每个消费者关联的Sequence, 尾部会再追加上 WorkPool的workSequence. **/
    @Override
    public Sequence[] getSequences() { return workerPool.getWorkerSequences(); }

    @Override
    public SequenceBarrier getBarrier() { return sequenceBarrier; }

    @Override
    public boolean isEndOfChain() { return endOfChain; }

    /** 开始执行当前 WorkPool. **/
    @Override
    public void start(Executor executor) { workerPool.start(executor); }

    @Override
    public void halt() { workerPool.halt(); }

    @Override
    public void markAsUsedInBarrier() { endOfChain = false; }

    @Override
    public boolean isRunning() { return workerPool.isRunning(); }
}
