/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.reactor;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import org.springframework.cloud.sleuth.CurrentTraceContext;
import org.springframework.cloud.sleuth.TraceContext;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.internal.LazyBean;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Reactive Span pointcuts factories.
 *
 * @author Stephane Maldini
 * @since 2.0.0
 */
// TODO: this is public as it is used out of package, but unlikely intended to be
// non-internal
public abstract class ReactorSleuth {

	private static final Log log = LogFactory.getLog(ReactorSleuth.class);

	private ReactorSleuth() {
	}

	/**
	 * Return a span operator pointcut given a Tracing. This can be used in reactor via
	 * {@link reactor.core.publisher.Flux#transform(Function)},
	 * {@link reactor.core.publisher.Mono#transform(Function)},
	 * {@link reactor.core.publisher.Hooks#onLastOperator(Function)} or
	 * {@link reactor.core.publisher.Hooks#onLastOperator(Function)}. The Span operator
	 * pointcut will pass the Scope of the Span without ever creating any new spans.
	 * @param springContext the Spring context.
	 * @param <T> an arbitrary type that is left unchanged by the span operator
	 * @return a new lazy span operator pointcut
	 */
	// Much of Boot assumes that the Spring context will be a
	// ConfigurableApplicationContext, rooted in SpringApplication's
	// requirement for it to be so. Previous versions of Reactor
	// instrumentation injected both BeanFactory and also
	// ConfigurableApplicationContext. This chooses the more narrow
	// signature as it is simpler than explaining instanceof checks.
	public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> scopePassingSpanOperator(
			ConfigurableApplicationContext springContext) {
		if (log.isTraceEnabled()) {
			log.trace("Scope passing operator [" + springContext + "]");
		}

		// keep a reference outside the lambda so that any caching will be visible to
		// all publishers
		LazyBean<CurrentTraceContext> lazyCurrentTraceContext = LazyBean.create(springContext,
				CurrentTraceContext.class);

		LazyBean<Tracer> lazyTracer = LazyBean.create(springContext, Tracer.class);

		return Operators.liftPublisher(p -> !(p instanceof Fuseable.ScalarCallable),
				(BiFunction) liftFunction(springContext, lazyCurrentTraceContext, lazyTracer));
	}

	/**
	 * Creates scope passing span operator which applies only to not
	 * {@code Scannable.Attr.RunStyle.SYNC} {@code Publisher}s. Used by
	 * {@code InstrumentationType#DECORATE_ON_EACH}
	 * @param springContext the Spring context.
	 * @param <T> an arbitrary type that is left unchanged by the span operator.
	 * @return operator to apply to {@link Hooks#onEachOperator(Function)}.
	 */
	public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> onEachOperatorForOnEachInstrumentation(
			ConfigurableApplicationContext springContext) {
		if (log.isTraceEnabled()) {
			log.trace("Scope passing operator [" + springContext + "]");
		}

		// keep a reference outside the lambda so that any caching will be visible to
		// all publishers
		LazyBean<CurrentTraceContext> lazyCurrentTraceContext = LazyBean.create(springContext,
				CurrentTraceContext.class);

		LazyBean<Tracer> lazyTracer = LazyBean.create(springContext, Tracer.class);

		@SuppressWarnings("rawtypes")
		Predicate<Publisher> shouldDecorate = ReactorHooksHelper::shouldDecorate;
		@SuppressWarnings("rawtypes")
		BiFunction<Publisher, ? super CoreSubscriber<? super T>, ? extends CoreSubscriber<? super T>> lifter = liftFunction(
				springContext, lazyCurrentTraceContext, lazyTracer);

		return ReactorHooksHelper.liftPublisher(shouldDecorate, lifter);
	}

	static <O> BiFunction<Publisher, ? super CoreSubscriber<? super O>, ? extends CoreSubscriber<? super O>> liftFunction(
			ConfigurableApplicationContext springContext, LazyBean<CurrentTraceContext> lazyCurrentTraceContext,
			LazyBean<Tracer> lazyTracer) {
		return (p, sub) -> {
			if (!springContext.isActive()) {
				if (log.isTraceEnabled()) {
					String message = "Spring Context [" + springContext
							+ "] is not yet refreshed. This is unexpected. Reactor Context is [" + context(sub)
							+ "] and name is [" + name(sub) + "]";
					log.trace(message);
				}
				return sub;
			}

			Context context = context(sub);

			if (log.isTraceEnabled()) {
				log.trace("Spring context [" + springContext + "], Reactor context [" + context + "], name ["
						+ name(sub) + "]");
			}

			// Try to get the current trace context bean, lenient when there are problems
			CurrentTraceContext currentTraceContext = lazyCurrentTraceContext.get();
			if (currentTraceContext == null) {
				if (log.isTraceEnabled()) {
					String message = "Spring Context [" + springContext
							+ "] did not return a CurrentTraceContext. Reactor Context is [" + context
							+ "] and name is [" + name(sub) + "]";
					log.trace(message);
				}
				return sub;
			}

			TraceContext parent = traceContext(context, currentTraceContext);
			if (parent == null) {
				return sub; // no need to scope a null parent
			}

			// Handle scenarios such as Mono.defer
			if (sub instanceof ScopePassingSpanSubscriber) {
				ScopePassingSpanSubscriber<?> scopePassing = (ScopePassingSpanSubscriber<?>) sub;
				if (scopePassing.parent.equals(parent)) {
					return sub; // don't double-wrap
				}
			}

			context = contextWithBeans(context, lazyTracer, lazyCurrentTraceContext);
			if (log.isTraceEnabled()) {
				log.trace("Spring context [" + springContext + "], Reactor context [" + context + "], name ["
						+ name(sub) + "]");
			}

			if (log.isTraceEnabled()) {
				log.trace("Creating a scope passing span subscriber with Reactor Context " + "[" + context
						+ "] and name [" + name(sub) + "]");
			}

			return new ScopePassingSpanSubscriber<>(sub, context, currentTraceContext, parent);
		};
	}

	private static <T> Context contextWithBeans(Context context, LazyBean<Tracer> tracer,
			LazyBean<CurrentTraceContext> currentTraceContext) {
		if (!context.hasKey(Tracer.class)) {
			context = context.put(Tracer.class, tracer.getOrError());
		}
		if (!context.hasKey(CurrentTraceContext.class)) {
			context = context.put(CurrentTraceContext.class, currentTraceContext.getOrError());
		}
		return context;
	}

	/**
	 * Creates a context with beans in it.
	 * @param springContext spring context
	 * @param <T> an arbitrary type that is left unchanged by the span operator
	 * @return a new operator pointcut that has beans in the context
	 */
	public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> springContextSpanOperator(
			ConfigurableApplicationContext springContext) {
		if (log.isTraceEnabled()) {
			log.trace("Spring Context passing operator [" + springContext + "]");
		}

		LazyBean<Tracer> lazyTracer = LazyBean.create(springContext, Tracer.class);
		LazyBean<CurrentTraceContext> lazyCurrentTraceContext = LazyBean.create(springContext,
				CurrentTraceContext.class);

		return Operators.liftPublisher(p -> {
			// We don't scope scalar results as they happen in an instant. This prevents
			// excessive overhead when using Flux/Mono #just, #empty, #error, etc.
			return !(p instanceof Fuseable.ScalarCallable) && springContext.isActive();
		}, (p, sub) -> {
			Context ctxBefore = context(sub);
			Context context = contextWithBeans(ctxBefore, lazyTracer, lazyCurrentTraceContext);
			if (context == ctxBefore) {
				return sub;
			}
			return new SleuthContextOperator<>(context, sub);
		});
	}

	/**
	 * Creates tracing context capturing reactor operator. Used by
	 * {@code InstrumentationType#DECORATE_ON_EACH}.
	 * @param springContext the Spring context.
	 * @param <T> an arbitrary type that is left unchanged by the span operator.
	 * @return operator to apply to {@link Hooks#onLastOperator(Function)} for
	 * {@code InstrumentationType#DECORATE_ON_EACH}
	 */
	public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> onLastOperatorForOnEachInstrumentation(
			ConfigurableApplicationContext springContext) {
		LazyBean<CurrentTraceContext> lazyCurrentTraceContext = LazyBean.create(springContext,
				CurrentTraceContext.class);
		LazyBean<Tracer> lazyTracer = LazyBean.create(springContext, Tracer.class);

		BiFunction<Publisher, ? super CoreSubscriber<? super T>, ? extends CoreSubscriber<? super T>> scopePassingSpanSubscriber = liftFunction(
				springContext, lazyCurrentTraceContext, lazyTracer);

		BiFunction<Publisher, ? super CoreSubscriber<? super T>, ? extends CoreSubscriber<? super T>> skipIfNoTraceCtx = (
				pub, sub) -> {
			// lazyCurrentTraceContext.get() is not null here. see predicate bellow
			TraceContext traceContext = lazyCurrentTraceContext.get().context();
			if (context(sub).getOrDefault(TraceContext.class, null) == traceContext) {
				return sub;
			}
			return scopePassingSpanSubscriber.apply(pub, sub);
		};

		return ReactorHooksHelper.liftPublisher(p -> {
			/*
			 * this prevent double decoration when last operator in the chain is not SYNC
			 * like {@code Mono.fromSuppler(() -> ...).subscribeOn(Schedulers.parallel())}
			 */
			if (ReactorHooksHelper.isTraceContextPropagator(p)) {
				return false;
			}
			boolean addContext = !(p instanceof Fuseable.ScalarCallable) && springContext.isActive();
			if (addContext) {
				CurrentTraceContext currentTraceContext = lazyCurrentTraceContext.get();
				if (currentTraceContext != null) {
					addContext = currentTraceContext.context() != null;
				}
			}
			return addContext;
		}, skipIfNoTraceCtx);
	}

	private static <T> Context context(CoreSubscriber<? super T> sub) {
		try {
			return sub.currentContext();
		}
		catch (Exception ex) {
			if (log.isDebugEnabled()) {
				log.debug("Exception occurred while trying to retrieve the context", ex);
			}
		}
		return Context.empty();
	}

	static String name(CoreSubscriber<?> sub) {
		return Scannable.from(sub).name();
	}

	/**
	 * Like {@link CurrentTraceContext#context()}, except it first checks the reactor
	 * context.
	 */
	static TraceContext traceContext(Context context, CurrentTraceContext fallback) {
		if (context.hasKey(TraceContext.class)) {
			return context.get(TraceContext.class);
		}
		return fallback.context();
	}

	public static Function<Runnable, Runnable> scopePassingOnScheduleHook(
			ConfigurableApplicationContext springContext) {
		LazyBean<CurrentTraceContext> lazyCurrentTraceContext = LazyBean.create(springContext,
				CurrentTraceContext.class);
		return delegate -> {
			if (springContext.isActive()) {
				final CurrentTraceContext currentTraceContext = lazyCurrentTraceContext.get();
				if (currentTraceContext == null) {
					return delegate;
				}
				final TraceContext traceContext = currentTraceContext.context();
				return () -> {
					try (CurrentTraceContext.Scope scope = currentTraceContext.maybeScope(traceContext)) {
						delegate.run();
					}
				};
			}
			return delegate;
		};
	}

}

class SleuthContextOperator<T> implements Subscription, CoreSubscriber<T>, Scannable {

	private final Context context;

	private final Subscriber<? super T> subscriber;

	private Subscription s;

	SleuthContextOperator(Context context, Subscriber<? super T> subscriber) {
		this.context = context;
		this.subscriber = subscriber;
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.s = subscription;
		this.subscriber.onSubscribe(this);
	}

	@Override
	public void request(long n) {
		this.s.request(n);
	}

	@Override
	public void cancel() {
		this.s.cancel();
	}

	@Override
	public void onNext(T o) {
		this.subscriber.onNext(o);
	}

	@Override
	public void onError(Throwable throwable) {
		this.subscriber.onError(throwable);
	}

	@Override
	public void onComplete() {
		this.subscriber.onComplete();
	}

	@Override
	public Context currentContext() {
		return this.context;
	}

	@Nullable
	@Override
	public Object scanUnsafe(Attr key) {
		if (key == Attr.RUN_STYLE) {
			return Attr.RunStyle.SYNC;
		}
		return null;
	}

}
