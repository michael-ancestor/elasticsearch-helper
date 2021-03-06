package org.xbib.metrics;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A registry of metric instances.
 */
public class MetricRegistry implements MetricSet {

    private final ConcurrentMap<MetricName, Metric> metrics;
    private final List<MetricRegistryListener> listeners;

    /**
     * @see #name(String, String...)
     * @param klass the class
     * @param names The remaining elements of the name
     * @return A metric name matching the specified components.
     */
    public static MetricName name(Class<?> klass, String... names) {
        return name(klass.getName(), names);
    }

    /**
     * Shorthand method for backwards compatibility in creating metric names.
     *
     * Uses {@link MetricName#build(String...)} for its
     * heavy lifting.
     *
     * @see MetricName#build(String...)
     * @param name The first element of the name
     * @param names The remaining elements of the name
     * @return A metric name matching the specified components.
     */
    public static MetricName name(String name, String... names) {
        final int length;
        if (names == null) {
            length = 0;
        } else {
            length = names.length;
        }
        final String[] parts = new String[length + 1];
        parts[0] = name;
        System.arraycopy(names, 0, parts, 1, length);
        return MetricName.build(parts);
    }

    /**
     * Creates a new {@link MetricRegistry}.
     */
    public MetricRegistry() {
        this(new ConcurrentHashMap<MetricName, Metric>());
    }

    /**
     * Creates a {@link MetricRegistry} with a custom {@link ConcurrentMap} implementation for use
     * inside the registry. Call as the super-constructor to create a {@link MetricRegistry} with
     * space- or time-bounded metric lifecycles, for example.
     * @param metricsMap metrics map
     */
    protected MetricRegistry(ConcurrentMap<MetricName, Metric> metricsMap) {
        this.metrics = metricsMap;
        this.listeners = new CopyOnWriteArrayList<MetricRegistryListener>();
    }

    /**
     * @see #register(MetricName, Metric)
     * @param name the metric name
     * @param metric the metric
     * @param <T>    the type of the metric
     * @return {@code metric}
     */
    @SuppressWarnings("unchecked")
    public <T extends Metric> T register(String name, T metric) throws IllegalArgumentException {
        return register(MetricName.build(name), metric);
    }

    /**
     * Given a {@link Metric}, registers it under the given name.
     *
     * @param name   the name of the metric
     * @param metric the metric
     * @param <T>    the type of the metric
     * @return {@code metric}
     * @throws IllegalArgumentException if the name is already registered
     */
    @SuppressWarnings("unchecked")
    public <T extends Metric> T register(MetricName name, T metric) throws IllegalArgumentException {
        if (metric instanceof MetricSet) {
            registerAll(name, (MetricSet) metric);
        } else {
            final Metric existing = metrics.putIfAbsent(name, metric);
            if (existing == null) {
                onMetricAdded(name, metric);
            } else {
                throw new IllegalArgumentException("A metric named " + name + " already exists");
            }
        }

        return metric;
    }

    /**
     * Given a metric set, registers them.
     *
     * @param metrics    a set of metrics
     * @throws IllegalArgumentException if any of the names are already registered
     */
    public void registerAll(MetricSet metrics) throws IllegalArgumentException {
        registerAll(null, metrics);
    }

    /**
     * @see #counter(MetricName)
     * @param name the name of the metric
     * @return a new or pre-existing {@link CountMetric}
     */
    public CountMetric counter(String name) {
        return counter(MetricName.build(name));
    }

    /**
     * Return the {@link CountMetric} registered under this name; or create and register
     * a new {@link CountMetric} if none is registered.
     *
     * @param name the name of the metric
     * @return a new or pre-existing {@link CountMetric}
     */
    public CountMetric counter(MetricName name) {
        return getOrAdd(name, MetricBuilder.COUNTERS);
    }

    /**
     * @see #histogram(MetricName)
     * @param name the name of the metric
     * @return a new or pre-existing {@link Histogram}
     */
    public Histogram histogram(String name) {
        return histogram(MetricName.build(name));
    }

    /**
     * Return the {@link Histogram} registered under this name; or create and register
     * a new {@link Histogram} if none is registered.
     *
     * @param name the name of the metric
     * @return a new or pre-existing {@link Histogram}
     */
    public Histogram histogram(MetricName name) {
        return getOrAdd(name, MetricBuilder.HISTOGRAMS);
    }

    /**
     * @see #meter(MetricName)
     * @param name the name of the metric
     * @return a new or pre-existing {@link Meter}
     */
    public Meter meter(String name) {
        return meter(MetricName.build(name));
    }

    /**
     * Return the {@link Meter} registered under this name; or create and register
     * a new {@link Meter} if none is registered.
     *
     * @param name the name of the metric
     * @return a new or pre-existing {@link Meter}
     */
    public Meter meter(MetricName name) {
        return getOrAdd(name, MetricBuilder.METERS);
    }

    /**
     * @see #timer(MetricName)
     * @param name the name of the metric
     * @return a new or pre-existing {@link Sampler}
     */
    public Sampler timer(String name) {
        return timer(MetricName.build(name));
    }

    /**
     * Return the {@link Sampler} registered under this name; or create and register
     * a new {@link Sampler} if none is registered.
     *
     * @param name the name of the metric
     * @return a new or pre-existing {@link Sampler}
     */
    public Sampler timer(MetricName name) {
        return getOrAdd(name, MetricBuilder.TIMERS);
    }

    /**
     * Removes the metric with the given name.
     *
     * @param name the name of the metric
     * @return whether or not the metric was removed
     */
    public boolean remove(MetricName name) {
        final Metric metric = metrics.remove(name);
        if (metric != null) {
            onMetricRemoved(name, metric);
            return true;
        }
        return false;
    }

    /**
     * Removes all metrics which match the given filter.
     *
     * @param filter a filter
     */
    public void removeMatching(MetricFilter filter) {
        for (Map.Entry<MetricName, Metric> entry : metrics.entrySet()) {
            if (filter.matches(entry.getKey(), entry.getValue())) {
                remove(entry.getKey());
            }
        }
    }

    /**
     * Adds a {@link MetricRegistryListener} to a collection of listeners that will be notified on
     * metric creation.  Listeners will be notified in the order in which they are added.
     * The listener will be notified of all existing metrics when it first registers.
     *
     * @param listener the listener that will be notified
     */
    public void addListener(MetricRegistryListener listener) {
        listeners.add(listener);

        for (Map.Entry<MetricName, Metric> entry : metrics.entrySet()) {
            notifyListenerOfAddedMetric(listener, entry.getValue(), entry.getKey());
        }
    }

    /**
     * Removes a {@link MetricRegistryListener} from this registry's collection of listeners.
     *
     * @param listener the listener that will be removed
     */
    public void removeListener(MetricRegistryListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns a set of the names of all the metrics in the registry.
     *
     * @return the names of all the metrics
     */
    public SortedSet<MetricName> getNames() {
        return Collections.unmodifiableSortedSet(new TreeSet<MetricName>(metrics.keySet()));
    }

    /**
     * Returns a map of all the gauges in the registry and their names.
     *
     * @return all the gauges in the registry
     */
    public SortedMap<MetricName, Gauge> getGauges() {
        return getGauges(MetricFilter.ALL);
    }

    /**
     * Returns a map of all the gauges in the registry and their names which match the given filter.
     *
     * @param filter    the metric filter to match
     * @return all the gauges in the registry
     */
    public SortedMap<MetricName, Gauge> getGauges(MetricFilter filter) {
        return getMetrics(Gauge.class, filter);
    }

    /**
     * Returns a map of all the counters in the registry and their names.
     *
     * @return all the counters in the registry
     */
    public SortedMap<MetricName, CountMetric> getCounters() {
        return getCounters(MetricFilter.ALL);
    }

    /**
     * Returns a map of all the counters in the registry and their names which match the given
     * filter.
     *
     * @param filter    the metric filter to match
     * @return all the counters in the registry
     */
    public SortedMap<MetricName, CountMetric> getCounters(MetricFilter filter) {
        return getMetrics(CountMetric.class, filter);
    }

    /**
     * Returns a map of all the histograms in the registry and their names.
     *
     * @return all the histograms in the registry
     */
    public SortedMap<MetricName, Histogram> getHistograms() {
        return getHistograms(MetricFilter.ALL);
    }

    /**
     * Returns a map of all the histograms in the registry and their names which match the given
     * filter.
     *
     * @param filter    the metric filter to match
     * @return all the histograms in the registry
     */
    public SortedMap<MetricName, Histogram> getHistograms(MetricFilter filter) {
        return getMetrics(Histogram.class, filter);
    }

    /**
     * Returns a map of all the meters in the registry and their names.
     *
     * @return all the meters in the registry
     */
    public SortedMap<MetricName, Meter> getMeters() {
        return getMeters(MetricFilter.ALL);
    }

    /**
     * Returns a map of all the meters in the registry and their names which match the given filter.
     *
     * @param filter    the metric filter to match
     * @return all the meters in the registry
     */
    public SortedMap<MetricName, Meter> getMeters(MetricFilter filter) {
        return getMetrics(Meter.class, filter);
    }

    /**
     * Returns a map of all the timers in the registry and their names.
     *
     * @return all the timers in the registry
     */
    public SortedMap<MetricName, Sampler> getTimers() {
        return getTimers(MetricFilter.ALL);
    }

    /**
     * Returns a map of all the timers in the registry and their names which match the given filter.
     *
     * @param filter    the metric filter to match
     * @return all the timers in the registry
     */
    public SortedMap<MetricName, Sampler> getTimers(MetricFilter filter) {
        return getMetrics(Sampler.class, filter);
    }

    @SuppressWarnings("unchecked")
    private <T extends Metric> T getOrAdd(MetricName name, MetricBuilder<T> builder) {
        final Metric metric = metrics.get(name);
        if (builder.isInstance(metric)) {
            return (T) metric;
        } else if (metric == null) {
            try {
                return register(name, builder.newMetric());
            } catch (IllegalArgumentException e) {
                final Metric added = metrics.get(name);
                if (builder.isInstance(added)) {
                    return (T) added;
                }
            }
        }
        throw new IllegalArgumentException(name + " is already used for a different type of metric");
    }

    @SuppressWarnings("unchecked")
    private <T extends Metric> SortedMap<MetricName, T> getMetrics(Class<T> klass, MetricFilter filter) {
        final TreeMap<MetricName, T> timers = new TreeMap<MetricName, T>();
        for (Map.Entry<MetricName, Metric> entry : metrics.entrySet()) {
            if (klass.isInstance(entry.getValue()) && filter.matches(entry.getKey(),
                    entry.getValue())) {
                timers.put(entry.getKey(), (T) entry.getValue());
            }
        }
        return Collections.unmodifiableSortedMap(timers);
    }

    private void onMetricAdded(MetricName name, Metric metric) {
        for (MetricRegistryListener listener : listeners) {
            notifyListenerOfAddedMetric(listener, metric, name);
        }
    }

    private void notifyListenerOfAddedMetric(MetricRegistryListener listener, Metric metric, MetricName name) {
        if (metric instanceof Gauge) {
            listener.onGaugeAdded(name, (Gauge<?>) metric);
        } else if (metric instanceof CountMetric) {
            listener.onCounterAdded(name, (CountMetric) metric);
        } else if (metric instanceof Histogram) {
            listener.onHistogramAdded(name, (Histogram) metric);
        } else if (metric instanceof Meter) {
            listener.onMeterAdded(name, (Meter) metric);
        } else if (metric instanceof Sampler) {
            listener.onTimerAdded(name, (Sampler) metric);
        } else {
            throw new IllegalArgumentException("Unknown metric type: " + metric.getClass());
        }
    }

    private void onMetricRemoved(MetricName name, Metric metric) {
        for (MetricRegistryListener listener : listeners) {
            notifyListenerOfRemovedMetric(name, metric, listener);
        }
    }

    private void notifyListenerOfRemovedMetric(MetricName name, Metric metric, MetricRegistryListener listener) {
        if (metric instanceof Gauge) {
            listener.onGaugeRemoved(name);
        } else if (metric instanceof CountMetric) {
            listener.onCounterRemoved(name);
        } else if (metric instanceof Histogram) {
            listener.onHistogramRemoved(name);
        } else if (metric instanceof Meter) {
            listener.onMeterRemoved(name);
        } else if (metric instanceof Sampler) {
            listener.onTimerRemoved(name);
        } else {
            throw new IllegalArgumentException("Unknown metric type: " + metric.getClass());
        }
    }

    private void registerAll(MetricName prefix, MetricSet metrics) throws IllegalArgumentException {
        if (prefix == null)
            prefix = MetricName.EMPTY;

        for (Map.Entry<MetricName, Metric> entry : metrics.getMetrics().entrySet()) {
            if (entry.getValue() instanceof MetricSet) {
                registerAll(MetricName.join(prefix, entry.getKey()), (MetricSet) entry.getValue());
            } else {
                register(MetricName.join(prefix, entry.getKey()), entry.getValue());
            }
        }
    }

    @Override
    public Map<MetricName, Metric> getMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    /**
     * A quick and easy way of capturing the notion of default metrics.
     */
    private interface MetricBuilder<T extends Metric> {
        MetricBuilder<CountMetric> COUNTERS = new MetricBuilder<CountMetric>() {
            @Override
            public CountMetric newMetric() {
                return new CountMetric();
            }

            @Override
            public boolean isInstance(Metric metric) {
                return CountMetric.class.isInstance(metric);
            }
        };

        MetricBuilder<Histogram> HISTOGRAMS = new MetricBuilder<Histogram>() {
            @Override
            public Histogram newMetric() {
                return new Histogram(new ExponentiallyDecayingReservoir());
            }

            @Override
            public boolean isInstance(Metric metric) {
                return Histogram.class.isInstance(metric);
            }
        };

        MetricBuilder<Meter> METERS = new MetricBuilder<Meter>() {
            @Override
            public Meter newMetric() {
                return new Meter();
            }

            @Override
            public boolean isInstance(Metric metric) {
                return Meter.class.isInstance(metric);
            }
        };

        MetricBuilder<Sampler> TIMERS = new MetricBuilder<Sampler>() {
            @Override
            public Sampler newMetric() {
                return new Sampler();
            }

            @Override
            public boolean isInstance(Metric metric) {
                return Sampler.class.isInstance(metric);
            }
        };

        T newMetric();

        boolean isInstance(Metric metric);
    }
}
