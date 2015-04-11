package java.util.stream;

import java.util.Spliterator;
import java.util.function.IntFunction;

abstract class PipelineHelper<P_OUT> {

    abstract StreamShape getSourceShape();

    abstract int getStreamAndOpFlags();

    abstract<P_IN> long exactOutputSizeIfKnown(Spliterator<P_IN> spliterator);

    abstract<P_IN, S extends Sink<P_OUT>> S wrapAndCopyInto(S sink, Spliterator<P_IN> spliterator);

    abstract<P_IN> void copyInto(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator);

    abstract <P_IN> void copyIntoWithCancel(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator);

    abstract<P_IN> Sink<P_IN> wrapSink(Sink<P_OUT> sink);

    abstract<P_IN> Spliterator<P_OUT> wrapSpliterator(Spliterator<P_IN> spliterator);

    abstract Node.Builder<P_OUT> makeNodeBuilder(long exactSizeIfKnown,
                                                 IntFunction<P_OUT[]> generator);

    abstract<P_IN> Node<P_OUT> evaluate(Spliterator<P_IN> spliterator,
                                        boolean flatten,
                                        IntFunction<P_OUT[]> generator);
}
