package org.mozilla.telemetry.event;

@FunctionalInterface
public interface TelemetryEventHandler {

    /**
     * Handle a telemetry event
     * @param e the event
     * @return true if default event handling should be applied, otherwise false.
     */
    boolean handleEvent(TelemetryEvent e);
}
