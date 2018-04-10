/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.telemetry;

import android.support.annotation.NonNull;
import android.support.annotation.RestrictTo;
import android.support.annotation.VisibleForTesting;

import org.mozilla.telemetry.config.TelemetryConfiguration;
import org.mozilla.telemetry.event.TelemetryEvent;
import org.mozilla.telemetry.event.TelemetryEventHandler;
import org.mozilla.telemetry.measurement.DefaultSearchMeasurement;
import org.mozilla.telemetry.measurement.EventsMeasurement;
import org.mozilla.telemetry.net.TelemetryClient;
import org.mozilla.telemetry.ping.TelemetryCorePingBuilder;
import org.mozilla.telemetry.ping.TelemetryEventPingBuilder;
import org.mozilla.telemetry.ping.TelemetryMobileEventPingBuilder;
import org.mozilla.telemetry.ping.TelemetryPing;
import org.mozilla.telemetry.ping.TelemetryPingBuilder;
import org.mozilla.telemetry.schedule.TelemetryScheduler;
import org.mozilla.telemetry.storage.TelemetryStorage;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Telemetry {
    private TelemetryConfiguration configuration;
    private TelemetryStorage storage;
    private TelemetryClient client;
    private TelemetryScheduler scheduler;
    private TelemetryEventHandler eventHandler;
    private TelemetryEventHandler defaultEventHandler = createDefaultEventHandler();

    private Map<String, TelemetryPingBuilder> pingBuilders;
    private ExecutorService executor;

    private Telemetry() {}

    private static Telemetry INSTANCE = new Telemetry();

    public static Telemetry initialize(TelemetryConfiguration configuration, TelemetryStorage storage,
                         TelemetryClient client, TelemetryScheduler scheduler) {

        // TODO add non-null assertion for all parameters

        if (INSTANCE.configuration != null) {
            throw new RuntimeException("Telemetry subsystem can only be initialized once!");
        }

        INSTANCE.configuration = configuration;
        INSTANCE.storage = storage;
        INSTANCE.client = client;
        INSTANCE.scheduler = scheduler;
        INSTANCE.pingBuilders = new HashMap<>();
        INSTANCE.executor = Executors.newSingleThreadExecutor();

        return INSTANCE;
    }

    public static Telemetry initialize(TelemetryConfiguration configuration, TelemetryStorage storage,
                                       TelemetryClient client, TelemetryScheduler scheduler,
                                       TelemetryEventHandler handler) {

        initialize(configuration, storage, client, scheduler);
        INSTANCE.eventHandler = handler;
        return INSTANCE;
    }

    public static Telemetry get() {
        if (INSTANCE.configuration == null) {
            throw new RuntimeException("Telemetry subsystem not initialized!");
        }
        return INSTANCE;
    }

    public static void record(TelemetryEvent event) {
        if (INSTANCE.configuration != null) {
            INSTANCE.defaultEventHandler.handleEvent(event);
        }
    }

    public static void shutdown() {
        INSTANCE.executor.shutdown();
        INSTANCE.configuration = null;
        INSTANCE.storage = null;
        INSTANCE.client = null;
        INSTANCE.scheduler = null;
        INSTANCE.eventHandler = null;
        INSTANCE.pingBuilders = null;
    }

    private static TelemetryEventHandler createDefaultEventHandler() {
        return new TelemetryEventHandler() {
            @Override
            public boolean handleEvent(TelemetryEvent e) {

                if (INSTANCE.eventHandler != null) {
                    if(INSTANCE.eventHandler.handleEvent(e)) {
                        INSTANCE.queueEvent(e);
                    }
                }
                else {
                    INSTANCE.queueEvent(e);
                }
                
                return false;
            }
        };
    }

    public Telemetry addPingBuilder(TelemetryPingBuilder builder) {
        pingBuilders.put(builder.getType(), builder);
        return this;
    }

    public Telemetry queuePing(final String pingType) {
        if (!configuration.isCollectionEnabled()) {
            return this;
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                final TelemetryPingBuilder pingBuilder = pingBuilders.get(pingType);

                if (!pingBuilder.canBuild()) {
                    // We do not always want to build a ping. Sometimes we want to collect enough data so that
                    // it is worth sending a ping. Here we exit early if the ping builder implementation
                    // signals that it's not time to build a ping yet.
                    return;
                }

                final TelemetryPing ping = pingBuilder.build();
                storage.store(ping);
            }
        });

        return this;
    }

    private void queueEvent(final TelemetryEvent event) {
        if (!configuration.isCollectionEnabled()) {
            return;
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                // We migrated from focus-event to mobile-event and unfortunately, this code was hard-coded to expect
                // a focus-event ping builder. We work around this by checking our new hardcoded code first for the new
                // ping type and then falling back on the legacy ping type.
                final TelemetryPingBuilder mobileEventBuilder = pingBuilders.get(TelemetryMobileEventPingBuilder.TYPE);
                final TelemetryPingBuilder focusEventBuilder = pingBuilders.get(TelemetryEventPingBuilder.TYPE);
                final EventsMeasurement measurement;
                final String addedPingType;
                if (mobileEventBuilder != null) {
                    measurement = ((TelemetryMobileEventPingBuilder) mobileEventBuilder).getEventsMeasurement();
                    addedPingType = mobileEventBuilder.getType();
                } else if (focusEventBuilder != null) {
                    measurement = ((TelemetryEventPingBuilder) focusEventBuilder).getEventsMeasurement();
                    addedPingType = focusEventBuilder.getType();
                } else {
                    throw new IllegalStateException("Expect either TelemetryEventPingBuilder or " +
                            "TelemetryMobileEventPingBuilder to be added to queue events");
                }

                measurement.add(event);
                if (measurement.getEventCount() >= configuration.getMaximumNumberOfEventsPerPing()) {
                    queuePing(addedPingType);
                }
            }
        });

        return;
    }

    public Collection<TelemetryPingBuilder> getBuilders() {
        return pingBuilders.values();
    }

    public Telemetry scheduleUpload() {
        if (!configuration.isUploadEnabled()) {
            return this;
        }

        executor.submit(new Runnable() {
            @Override
            public void run() {
                scheduler.scheduleUpload(configuration);
            }
        });
        return this;
    }

    public void recordSessionStart() {
        if (!configuration.isCollectionEnabled()) {
            return;
        }

        if (!pingBuilders.containsKey(TelemetryCorePingBuilder.TYPE)) {
            throw new IllegalStateException("This configuration does not contain a core ping builder");
        }

        final TelemetryCorePingBuilder builder = (TelemetryCorePingBuilder) pingBuilders.get(TelemetryCorePingBuilder.TYPE);

        builder.getSessionDurationMeasurement().recordSessionStart();
        builder.getSessionCountMeasurement().countSession();
    }

    public Telemetry recordSessionEnd() {
        if (!configuration.isCollectionEnabled()) {
            return this;
        }

        if (!pingBuilders.containsKey(TelemetryCorePingBuilder.TYPE)) {
            throw new IllegalStateException("This configuration does not contain a core ping builder");
        }

        final TelemetryCorePingBuilder builder = (TelemetryCorePingBuilder) pingBuilders.get(TelemetryCorePingBuilder.TYPE);
        builder.getSessionDurationMeasurement().recordSessionEnd();

        return this;
    }

    /**
     * Record a search for the given location and search engine identifier.
     *
     * Common location values used by Fennec and Focus:
     *
     * actionbar:  the user types in the url bar and hits enter to use the default search engine
     * listitem:   the user selects a search engine from the list of secondary search engines at
     *             the bottom of the screen
     * suggestion: the user clicks on a search suggestion or, in the case that suggestions are
     *             disabled, the row corresponding with the main engine
     *
     * @param location where search was started.
     * @param identifier of the used search engine.
     */
    public Telemetry recordSearch(@NonNull  String location, @NonNull String identifier) {
        if (!configuration.isCollectionEnabled()) {
            return this;
        }

        if (!pingBuilders.containsKey(TelemetryCorePingBuilder.TYPE)) {
            throw new IllegalStateException("This configuration does not contain a core ping builder");
        }

        final TelemetryCorePingBuilder builder = (TelemetryCorePingBuilder) pingBuilders.get(TelemetryCorePingBuilder.TYPE);
        builder.getSearchesMeasurement()
                .recordSearch(location, identifier);

        return this;
    }

    public Telemetry setDefaultSearchProvider(DefaultSearchMeasurement.DefaultSearchEngineProvider provider) {
        if (!pingBuilders.containsKey(TelemetryCorePingBuilder.TYPE)) {
            throw new IllegalStateException("This configuration does not contain a core ping builder");
        }

        final TelemetryCorePingBuilder builder = (TelemetryCorePingBuilder) pingBuilders.get(TelemetryCorePingBuilder.TYPE);
        builder.getDefaultSearchMeasurement()
                .setDefaultSearchEngineProvider(provider);

        return this;
    }

    public TelemetryClient getClient() {
        return client;
    }

    public TelemetryStorage getStorage() {
        return storage;
    }

    public TelemetryConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @VisibleForTesting ExecutorService getExecutor() {
        return executor;
    }
}
