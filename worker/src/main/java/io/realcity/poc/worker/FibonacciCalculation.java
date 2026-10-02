package io.realcity.poc.worker;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.ObservationKeyValue;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.observation.aop.Cardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Some slow, CPU-ish work, split into methods so each one shows up as its own span.
 * <p>
 * The public methods are spans through {@link Observed}: Spring wraps this bean in a proxy, so it only sees calls
 * that come from another bean, here {@link WorkerRunner}. A call inside this class, like {@link #next}, never goes
 * through the proxy; that one is observed in code instead.
 * <p>
 * Span attributes: a parameter becomes one through {@link ObservationKeyValue}; values that only exist inside the
 * method are added to the current {@link Observation}. All of them are high cardinality: span attributes only, never
 * metric tags.
 */
@Component
class FibonacciCalculation {

    private static final Logger log = LoggerFactory.getLogger(FibonacciCalculation.class);

    private final ObservationRegistry observations;

    FibonacciCalculation(ObservationRegistry observations) {
        this.observations = observations;
    }

    /** The first {@code count} Fibonacci numbers, one per second. */
    @Observed(name = "fibonacci")
    public List<BigInteger> fibonacci(
            @ObservationKeyValue(key = "fibonacci.count", cardinality = Cardinality.HIGH) int count) {
        List<BigInteger> numbers = new ArrayList<>();
        BigInteger a = BigInteger.ZERO;
        BigInteger b = BigInteger.ONE;
        for (int i = 1; i <= count; i++) {
            numbers.add(next(i, a));
            BigInteger sum = a.add(b);
            a = b;
            b = sum;
        }
        return numbers;
    }

    // Called from inside this class, so @Observed would be ignored here: create the span in code
    private BigInteger next(int index, BigInteger value) {
        return Observation.createNotStarted("fibonacci-number", observations)
                .highCardinalityKeyValue("fibonacci.index", String.valueOf(index))
                .highCardinalityKeyValue("fibonacci.value", value.toString())
                .observe(() -> {
                    sleepOneSecond();
                    log.info("fibonacci #{} = {}", index, value);
                    return value;
                });
    }

    @Observed(name = "fibonacci-summary")
    public void summary(List<BigInteger> numbers) {
        BigInteger sum = numbers.stream().reduce(BigInteger.ZERO, BigInteger::add);
        observations.getCurrentObservation() // this method's own observation, opened by @Observed
                .highCardinalityKeyValue("fibonacci.sum", sum.toString())
                .highCardinalityKeyValue("fibonacci.largest", numbers.getLast().toString());
        log.info("{} Fibonacci numbers, sum {}, largest {}", numbers.size(), sum, numbers.getLast());
    }

    private static void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
