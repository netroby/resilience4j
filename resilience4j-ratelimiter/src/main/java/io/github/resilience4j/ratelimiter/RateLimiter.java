/*
 *
 *  Copyright 2016 Robert Winkler and Bohdan Storozhuk
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.ratelimiter;

import io.github.resilience4j.core.EventConsumer;
import io.github.resilience4j.ratelimiter.event.RateLimiterEvent;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnFailureEvent;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnSuccessEvent;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import io.vavr.CheckedFunction0;
import io.vavr.CheckedFunction1;
import io.vavr.CheckedRunnable;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A RateLimiter instance is thread-safe can be used to decorate multiple requests.
 *
 * A RateLimiter distributes permits at a configurable rate. {@link #getPermission} blocks if necessary
 * until a permit is available, and then takes it. Once acquired, permits need not be released.
 */
public interface RateLimiter {

    /**
     * Creates a RateLimiter with a custom RateLimiter configuration.
     *
     * @param name              the name of the RateLimiter
     * @param rateLimiterConfig a custom RateLimiter configuration
     * @return The {@link RateLimiter}
     */
    static RateLimiter of(String name, RateLimiterConfig rateLimiterConfig) {
        return new AtomicRateLimiter(name, rateLimiterConfig);
    }

    /**
     * Creates a RateLimiter with a custom RateLimiterConfig configuration.
     *
     * @param name                      the name of the RateLimiter
     * @param rateLimiterConfigSupplier a supplier of a custom RateLimiterConfig configuration
     * @return The {@link RateLimiter}
     */
    static RateLimiter of(String name, Supplier<RateLimiterConfig> rateLimiterConfigSupplier) {
        return new AtomicRateLimiter(name, rateLimiterConfigSupplier.get());
    }

    /**
     * Creates a RateLimiter with a default RateLimiterConfig configuration.
     *
     * @param name                      the name of the RateLimiter
     * @return The {@link RateLimiter}
     */
    static RateLimiter ofDefaults(String name) {
        return new AtomicRateLimiter(name, RateLimiterConfig.ofDefaults());
    }

    /**
     * Returns a supplier which is decorated by a rateLimiter.
     *
     * @param rateLimiter the rateLimiter
     * @param supplier the original supplier
     * @param <T> the type of the returned CompletionStage's result
     * @return a supplier which is decorated by a RateLimiter.
     */
    static <T> Supplier<CompletionStage<T>> decorateCompletionStage(RateLimiter rateLimiter, Supplier<CompletionStage<T>> supplier) {
        return () -> {

            final CompletableFuture<T> promise = new CompletableFuture<>();
            try {
                waitForPermission(rateLimiter);
                supplier.get()
                    .whenComplete(
                        (result, throwable) -> {
                            if (throwable != null) {
                                promise.completeExceptionally(throwable);
                            } else {
                                promise.complete(result);
                            }
                        }
                    );
            } catch (Throwable throwable) {
                promise.completeExceptionally(throwable);
            }
            return promise;
        };
    }

    /**
     * Creates a supplier which is restricted by a RateLimiter.
     *
     * @param rateLimiter the RateLimiter
     * @param supplier    the original supplier
     * @param <T> the type of results supplied supplier
     * @return a supplier which is restricted by a RateLimiter.
     */
    static <T> CheckedFunction0<T> decorateCheckedSupplier(RateLimiter rateLimiter, CheckedFunction0<T> supplier) {
        return () -> {
            waitForPermission(rateLimiter);
            return supplier.apply();
        };
    }

    /**
     * Creates a runnable which is restricted by a RateLimiter.
     *
     * @param rateLimiter the RateLimiter
     * @param runnable    the original runnable
     * @return a runnable which is restricted by a RateLimiter.
     */
    static CheckedRunnable decorateCheckedRunnable(RateLimiter rateLimiter, CheckedRunnable runnable) {

        return () -> {
            waitForPermission(rateLimiter);
            runnable.run();
        };
    }

    /**
     * Creates a function which is restricted by a RateLimiter.
     *
     * @param rateLimiter the RateLimiter
     * @param function    the original function
     * @param <T> the type of function argument
     * @param <R> the type of function results
     * @return a function which is restricted by a RateLimiter.
     */
    static <T, R> CheckedFunction1<T, R> decorateCheckedFunction(RateLimiter rateLimiter, CheckedFunction1<T, R> function) {
        return (T t) -> {
            waitForPermission(rateLimiter);
            return function.apply(t);
        };
    }

    /**
     * Creates a supplier which is restricted by a RateLimiter.
     *
     * @param rateLimiter the RateLimiter
     * @param supplier    the original supplier
     * @param <T> the type of results supplied supplier
     * @return a supplier which is restricted by a RateLimiter.
     */
    static <T> Supplier<T> decorateSupplier(RateLimiter rateLimiter, Supplier<T> supplier) {
        return () -> {
            waitForPermission(rateLimiter);
            return supplier.get();
        };
    }

    static <T> Callable<T> decorateCallable(RateLimiter rateLimiter, Callable<T> callable) {
        return () -> {
            waitForPermission(rateLimiter);
            return callable.call();
        };
    }

    /**
     * Creates a consumer which is restricted by a RateLimiter.
     *
     * @param rateLimiter the RateLimiter
     * @param consumer    the original consumer
     * @param <T> the type of the input to the consumer
     * @return a consumer which is restricted by a RateLimiter.
     */
    static <T> Consumer<T> decorateConsumer(RateLimiter rateLimiter, Consumer<T> consumer) {
        return (T t) -> {
            waitForPermission(rateLimiter);
            consumer.accept(t);
        };
    }

    /**
     * Creates a runnable which is restricted by a RateLimiter.
     *
     * @param rateLimiter the RateLimiter
     * @param runnable    the original runnable
     * @return a runnable which is restricted by a RateLimiter.
     */
    static Runnable decorateRunnable(RateLimiter rateLimiter, Runnable runnable) {
        return () -> {
            waitForPermission(rateLimiter);
            runnable.run();
        };
    }


    /**
     * Creates a function which is restricted by a RateLimiter.
     *
     * @param rateLimiter the RateLimiter
     * @param function    the original function
     * @param <T> the type of the input to the function
     * @param <R> the type of the result of the function
     * @return a function which is restricted by a RateLimiter.
     */
    static <T, R> Function<T, R> decorateFunction(RateLimiter rateLimiter, Function<T, R> function) {
        return (T t) -> {
            waitForPermission(rateLimiter);
            return function.apply(t);
        };
    }

    /**
     * Will wait for permission within default timeout duration.
     *
     * @param rateLimiter the RateLimiter to get permission from
     * @throws RequestNotPermitted if waiting time elapsed before a permit was acquired.
     * @throws IllegalStateException if thread was interrupted during permission wait
     */
    static void waitForPermission(final RateLimiter rateLimiter) throws IllegalStateException, RequestNotPermitted {
        RateLimiterConfig rateLimiterConfig = rateLimiter.getRateLimiterConfig();
        Duration timeoutDuration = rateLimiterConfig.getTimeoutDuration();
        boolean permission = rateLimiter.getPermission(timeoutDuration);
        if (Thread.interrupted()) {
            throw new IllegalStateException("Thread was interrupted during permission wait");
        }
        if (!permission) {
            throw new RequestNotPermitted("Request not permitted for limiter: " + rateLimiter.getName());
        }
    }

    /**
     * Dynamic rate limiter configuration change.
     * This method allows to change timeout duration of current limiter.
     * NOTE! New timeout duration won't affect threads that are currently waiting for permission.
     * @param timeoutDuration new timeout duration
     */
    void changeTimeoutDuration(Duration timeoutDuration);

    /**
     * Dynamic rate limiter configuration change.
     * This method allows to change count of permissions available during refresh period.
     * NOTE! New limit won't affect current period permissions and will apply only from next one.
     * @param limitForPeriod new permissions limit
     */
    void changeLimitForPeriod(int limitForPeriod);

    /**
     * Acquires a permission from this rate limiter, blocking until one is
     * available.
     * <p>If the current thread is {@linkplain Thread#interrupt interrupted}
     * while waiting for a permit then it won't throw {@linkplain InterruptedException},
     * but its interrupt status will be set.
     *
     * @param timeoutDuration the maximum time to wait
     * @return {@code true} if a permit was acquired and {@code false}
     * if waiting timeoutDuration elapsed before a permit was acquired
     */
    boolean getPermission(Duration timeoutDuration);

    /**
     * Get the name of this RateLimiter
     *
     * @return the name of this RateLimiter
     */
    String getName();

    /**
     * Get the RateLimiterConfig of this RateLimiter.
     *
     * @return the RateLimiterConfig of this RateLimiter
     */
    RateLimiterConfig getRateLimiterConfig();

    /**
     * Get the Metrics of this RateLimiter.
     *
     * @return the Metrics of this RateLimiter
     */
    Metrics getMetrics();

    /**
     * Returns an EventPublisher which can be used to register event consumers.
     *
     * @return an EventPublisher
     */
    EventPublisher getEventPublisher();

    /**
     * Decorates and executes the decorated Supplier.
     *
     * @param supplier the original Supplier
     * @param <T> the type of results supplied by this supplier
     * @return the result of the decorated Supplier.
     */
    default <T> T executeSupplier(Supplier<T> supplier){
        return decorateSupplier(this, supplier).get();
    }

    /**
     * Decorates and executes the decorated Callable.
     *
     * @param callable the original Callable
     *
     * @return the result of the decorated Callable.
     * @param <T> the result type of callable
     * @throws Exception if unable to compute a result
     */
    default <T> T executeCallable(Callable<T> callable) throws Exception{
        return decorateCallable(this, callable).call();
    }

    /**
     * Decorates and executes the decorated Runnable.
     *
     * @param runnable the original Runnable
     */
    default void executeRunnable(Runnable runnable){
        decorateRunnable(this, runnable).run();
    }


    interface Metrics {
        /**
         * Returns an estimate of the number of threads waiting for permission
         * in this JVM process.
         * <p>This method is typically used for debugging and testing purposes.
         *
         * @return estimate of the number of threads waiting for permission.
         */
        int getNumberOfWaitingThreads();

        /**
         * Estimates count of available permissions.
         * Can be negative if some permissions where reserved.
         * <p>This method is typically used for debugging and testing purposes.
         *
         * @return estimated count of permissions
         */
        int getAvailablePermissions();
    }

    /**
     * An EventPublisher which can be used to register event consumers.
     */
    interface EventPublisher extends io.github.resilience4j.core.EventPublisher<RateLimiterEvent> {

        EventPublisher onSuccess(EventConsumer<RateLimiterOnSuccessEvent> eventConsumer);

        EventPublisher onFailure(EventConsumer<RateLimiterOnFailureEvent> eventConsumer);

    }
}
