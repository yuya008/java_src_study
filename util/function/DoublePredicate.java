package java.util.function;

import java.util.Objects;

@FunctionalInterface
public interface DoublePredicate {

    boolean test(double value);

    default DoublePredicate and(DoublePredicate other) {
        Objects.requireNonNull(other);
        return (value) -> test(value) && other.test(value);
    }

    default DoublePredicate negate() {
        return (value) -> !test(value);
    }

    default DoublePredicate or(DoublePredicate other) {
        Objects.requireNonNull(other);
        return (value) -> test(value) || other.test(value);
    }
}
