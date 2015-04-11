package java.util.function;

import java.util.Objects;

@FunctionalInterface
public interface LongUnaryOperator {

    long applyAsLong(long operand);

    default LongUnaryOperator compose(LongUnaryOperator before) {
        Objects.requireNonNull(before);
        return (long v) -> applyAsLong(before.applyAsLong(v));
    }

    default LongUnaryOperator andThen(LongUnaryOperator after) {
        Objects.requireNonNull(after);
        return (long t) -> after.applyAsLong(applyAsLong(t));
    }

    static LongUnaryOperator identity() {
        return t -> t;
    }
}
