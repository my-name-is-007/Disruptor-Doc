package com.lmax.disruptor;

/**
 * Experimental poll-based interface for the Disruptor.
 */
public class EventPoller<T> {
    private final DataProvider<T> dataProvider;
    private final Sequencer sequencer;
    private final Sequence sequence;
    private final Sequence gatingSequence;

    public interface Handler<T> {
        boolean onEvent(T event, long sequence, boolean endOfBatch) throws Exception;
    }

    public enum PollState {
        PROCESSING, GATING, IDLE
    }

    public EventPoller(
        final DataProvider<T> dataProvider, final Sequencer sequencer, final Sequence sequence, final Sequence gatingSequence) {
        this.dataProvider = dataProvider;
        this.sequencer = sequencer;
        this.sequence = sequence;
        this.gatingSequence = gatingSequence;
    }

    public PollState poll(final Handler<T> eventHandler) throws Exception {
        final long currentSequence = sequence.get();

        //要消费的下一个数据
        long nextSequence = currentSequence + 1;
        final long availableSequence = sequencer.getHighestPublishedSequence(nextSequence, gatingSequence.get());

        /**
         * 从下面这段代码我们可以看出，它的作用就是防止事件发布的时候, 当前发布的事件将正在处理的事件覆盖掉
         * 为了防止超过正在处理的事件它会在这个位置进行等待，知道可以发布为止
         * 所以从这段代码我们得出一个结论，就是在事件处理方法中不要发生阻塞，如果阻塞它会影响整个系统运行
         * 发生阻塞会使得整个系统都阻塞在一个地方(这也是 Disruptor的缺点)
         */
        if (nextSequence <= availableSequence) {
            boolean processNextEvent;
            long processedSequence = currentSequence;

            try {
                do {
                    final T event = dataProvider.get(nextSequence);
                    processNextEvent = eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    processedSequence = nextSequence;
                    nextSequence++;
                }
                while (nextSequence <= availableSequence & processNextEvent);
            }
            finally {
                sequence.set(processedSequence);
            }

            return PollState.PROCESSING;
        }
        else if (sequencer.getCursor() >= nextSequence)
        {
            return PollState.GATING;
        }
        else
        {
            return PollState.IDLE;
        }
    }

    public static <T> EventPoller<T> newInstance(final DataProvider<T> dataProvider, final Sequencer sequencer,
                                                 final Sequence sequence, final Sequence cursorSequence, final Sequence... gatingSequences) {
        Sequence gatingSequence;
        if (gatingSequences.length == 0) {
            gatingSequence = cursorSequence;
        }

        else if (gatingSequences.length == 1) {
            gatingSequence = gatingSequences[0];
        }

        else {
            gatingSequence = new FixedSequenceGroup(gatingSequences);
        }

        return new EventPoller<T>(dataProvider, sequencer, sequence, gatingSequence);
    }

    public Sequence getSequence() {
        return sequence;
    }
}
