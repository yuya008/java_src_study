package java.util.function;

import java.util.Objects;

@FunctionalInterface
public interface IntUnaryOperator {

    int applyAsInt(int operand);

    default IntUnaryOperator compose(IntUnaryOperator before) {
        Objects.requireNonNull(before);
        return (int v) -> applyAsInt(before.applyAsInt(v));
    }

    default IntUnaryOperator andThen(IntUnaryOperator after) {
        Objects.requireNonNull(after);
        return (int t) -> after.applyAsInt(applyAsInt(t));
    }

    static IntUnaryOperator identity() {
        return t -> t;
    }
}
