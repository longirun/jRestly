package ru.jrestly.http;

/**
 * Jitter strategies for exponential backoff between retry attempts (ADR-0040).
 *
 * <p>In distributed systems, adding randomness (jitter) to backoff intervals
 * prevents the <em>thundering herd</em> effect where concurrent clients retry
 * in lockstep after a transient outage and overwhelm recovering services.
 *
 * <p>Supported algorithms reflect recommendations from AWS Architecture Blog
 * ("Exponential Backoff And Jitter" by Marc Brooker):
 * <ul>
 *   <li>{@link #FULL}: uniform random draw in {@code [0, bound]}. Minimizes total
 *       server load across competing clients. Default in jRestly.</li>
 *   <li>{@link #EQUAL}: guarantees half the bound as a deterministic floor and
 *       randomizes the remaining half: {@code bound / 2 + random[0, bound / 2]}.
 *       Prevents too-early retries while maintaining low collision rate.</li>
 *   <li>{@link #DECORRELATED}: adaptive jitter independent of the attempt count,
 *       scaled from the previous sleep duration:
 *       {@code min(maxBackoff, random[initialBackoff, previousSleep * 3])}.</li>
 *   <li>{@link #NONE}: strictly deterministic exponential backoff (no randomness).
 *       Useful in predictable integration tests, background queues, or batch jobs.</li>
 * </ul>
 */
public enum Jitter {

    /**
     * Uniform random draw between 0 and the current backoff bound:
     * {@code sleep = random(0, bound)}.
     */
    FULL,

    /**
     * Equal jitter: half the bound is guaranteed, the other half is randomized:
     * {@code sleep = bound / 2 + random(0, bound / 2)}.
     */
    EQUAL,

    /**
     * Decorrelated jitter: computes each backoff interval from the previous wait
     * rather than the attempt number:
     * {@code sleep = min(maxBackoff, random(initialBackoff, previousSleep * 3))}.
     */
    DECORRELATED,

    /**
     * Deterministic exponential backoff with zero randomness:
     * {@code sleep = bound}.
     */
    NONE
}
