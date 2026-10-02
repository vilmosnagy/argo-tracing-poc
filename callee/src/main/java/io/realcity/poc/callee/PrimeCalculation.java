package io.realcity.poc.callee;

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
 * Some slow, CPU-ish work done while serving a request, split into methods so each one shows up as its own span.
 * <p>
 * The public methods are spans through {@link Observed}: Spring wraps this bean in a proxy, so it only sees calls
 * that come from another bean, here {@link StepController}. A call inside this class, like {@link #next}, never goes
 * through the proxy; that one is observed in code instead.
 * <p>
 * Span attributes: a parameter becomes one through {@link ObservationKeyValue}; values that only exist inside the
 * method are added to the current {@link Observation}. All of them are high cardinality: span attributes only, never
 * metric tags.
 */
@Component
class PrimeCalculation {

    private static final Logger log = LoggerFactory.getLogger(PrimeCalculation.class);

    private final ObservationRegistry observations;

    PrimeCalculation(ObservationRegistry observations) {
        this.observations = observations;
    }

    /** The first {@code count} prime numbers, one per second. */
    @Observed(name = "primes")
    public List<Long> primes(@ObservationKeyValue(key = "prime.count", cardinality = Cardinality.HIGH) int count) {
        List<Long> primes = new ArrayList<>();
        long candidate = 1;
        while (primes.size() < count) {
            candidate = next(primes.size() + 1, candidate);
            primes.add(candidate);
        }
        return primes;
    }

    // Called from inside this class, so @Observed would be ignored here: create the span in code
    private long next(int index, long previous) {
        Observation observation = Observation.createNotStarted("prime-number", observations)
                .highCardinalityKeyValue("prime.index", String.valueOf(index));
        return observation.observe(() -> {
            long candidate = previous + 1;
            while (!isPrime(candidate)) {
                candidate++;
            }
            sleepOneSecond();
            log.info("prime #{} = {}", index, candidate);
            observation.highCardinalityKeyValue("prime.value", String.valueOf(candidate));
            return candidate;
        });
    }

    /** Trial division by every odd number up to the square root. */
    private static boolean isPrime(long n) {
        if (n < 2) {
            return false;
        }
        if (n % 2 == 0) {
            return n == 2;
        }
        for (long d = 3; d * d <= n; d += 2) {
            if (n % d == 0) {
                return false;
            }
        }
        return true;
    }

    @Observed(name = "prime-summary")
    public void summary(List<Long> primes) {
        long sum = primes.stream().mapToLong(Long::longValue).sum();
        observations.getCurrentObservation() // this method's own observation, opened by @Observed
                .highCardinalityKeyValue("prime.sum", String.valueOf(sum))
                .highCardinalityKeyValue("prime.largest", String.valueOf(primes.getLast()));
        log.info("{} primes, sum {}, largest {}", primes.size(), sum, primes.getLast());
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
