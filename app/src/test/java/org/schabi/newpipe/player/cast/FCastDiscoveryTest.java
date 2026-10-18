package org.schabi.newpipe.player.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import org.fcast.sender_sdk.DeviceInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the real discovery state machine against deterministic NSD callback events. */
public class FCastDiscoveryTest {
    private final Map<String, NsdManager.ServiceInfoCallback> trackers = new LinkedHashMap<>();
    private final List<NsdManager.ServiceInfoCallback> unregistered = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private List<FCastDevice> devices = List.of();
    private MockedStatic<Looper> loopers;
    private MockedConstruction<Handler> handlers;
    private NsdManager.DiscoveryListener browser;
    private FCastDiscovery discovery;

    @Before
    public void setUp() throws Exception {
        final Looper main = mock(Looper.class);
        loopers = mockStatic(Looper.class);
        loopers.when(Looper::getMainLooper).thenReturn(main);
        loopers.when(Looper::myLooper).thenReturn(main);
        handlers = mockConstruction(Handler.class);
        final Context context = mock(Context.class);
        final NsdManager nsd = mock(NsdManager.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSystemService(NsdManager.class)).thenReturn(nsd);
        doAnswer(call -> {
            browser = call.getArgument(2);
            return null;
        }).when(nsd).discoverServices(anyString(), anyInt(), any());
        doAnswer(call -> {
            final NsdServiceInfo service = call.getArgument(0);
            trackers.put(service.getServiceName(), call.getArgument(2));
            return null;
        }).when(nsd).registerServiceInfoCallback(any(NsdServiceInfo.class), any(), any());
        doAnswer(call -> {
            final NsdManager.ServiceInfoCallback callback = call.getArgument(0);
            trackers.values().removeIf(value -> value == callback);
            unregistered.add(callback);
            return null;
        }).when(nsd).unregisterServiceInfoCallback(any());
        discovery = new FCastDiscovery(context, new FCastDiscovery.Listener() {
            @Override
            public void onDevicesChanged(final List<FCastDevice> snapshot) {
                devices = List.copyOf(snapshot);
            }

            @Override
            public void onError(final String message) {
                errors.add(message);
            }
        });
        // The local Android stub has no runtime SDK. Select the API 34+ tracking path explicitly.
        final Field tracking = FCastDiscovery.class.getDeclaredField("tracksServiceInfo");
        tracking.setAccessible(true);
        tracking.setBoolean(discovery, true);
        discovery.start();
    }

    @After
    public void tearDown() {
        discovery.close();
        handlers.close();
        loopers.close();
    }

    @Test
    public void trackingLossWithdrawsTheDeviceAndItsNextUpdateRestoresResolvedIdentity()
            throws Exception {
        final NsdManager.ServiceInfoCallback tracker = find("Living room");
        assertTrue(devices.isEmpty());
        tracker.onServiceUpdated(resolved("Living room", 2, "first-fingerprint"));
        assertDevice("Living room", "192.0.2.2", "first-fingerprint");

        tracker.onServiceLost();

        assertTrue(devices.isEmpty());
        assertSame(tracker, trackers.get("Living room"));
        assertTrue(unregistered.isEmpty());
        tracker.onServiceUpdated(resolved("Living room", 3, "recovered-fingerprint"));
        assertDevice("Living room", "192.0.2.3", "recovered-fingerprint");
        assertTrue(errors.isEmpty());
    }

    @Test
    public void browseLossPermanentlyRemovesTheTrackerWithoutBlockingANewDiscovery()
            throws Exception {
        final NsdManager.ServiceInfoCallback old = find("Living room");
        old.onServiceUpdated(resolved("Living room", 2, "old-fingerprint"));
        old.onServiceLost();

        browser.onServiceLost(service("Living room"));

        assertTrue(devices.isEmpty());
        assertFalse(trackers.containsKey("Living room"));
        assertEquals(List.of(old), unregistered);
        old.onServiceUpdated(resolved("Living room", 2, "old-fingerprint"));
        assertTrue(devices.isEmpty());

        final NsdManager.ServiceInfoCallback replacement = find("Living room");
        assertNotSame(old, replacement);
        old.onServiceUpdated(resolved("Living room", 2, "old-fingerprint"));
        assertTrue(devices.isEmpty());
        replacement.onServiceUpdated(resolved("Living room", 4, "new-fingerprint"));
        assertDevice("Living room", "192.0.2.4", "new-fingerprint");
    }

    @Test
    public void closeClearsThePublishedPickerSnapshotAndRejectsLateDiscoveryEvents()
            throws Exception {
        final NsdManager.ServiceInfoCallback tracker = find("Living room");
        tracker.onServiceUpdated(resolved("Living room", 2, "fingerprint"));
        assertDevice("Living room", "192.0.2.2", "fingerprint");

        discovery.close();

        assertTrue(devices.isEmpty());
        assertTrue(trackers.isEmpty());
        assertEquals(List.of(tracker), unregistered);
        tracker.onServiceUpdated(resolved("Living room", 3, "late-fingerprint"));
        browser.onServiceFound(service("Bedroom"));
        assertTrue(devices.isEmpty());
        assertTrue(trackers.isEmpty());
    }

    @Test
    public void closeAlsoUnregistersATrackerWhoseServiceInfoIsTemporarilyLost() throws Exception {
        final NsdManager.ServiceInfoCallback tracker = find("Living room");
        tracker.onServiceUpdated(resolved("Living room", 2, "fingerprint"));
        tracker.onServiceLost();

        discovery.close();

        assertTrue(trackers.isEmpty());
        assertEquals(List.of(tracker), unregistered);
        tracker.onServiceUpdated(resolved("Living room", 3, "late-fingerprint"));
        assertTrue(devices.isEmpty());
    }

    @Test
    public void discoveryFailureEndsThePublishedPickerSnapshot() throws Exception {
        final NsdManager.ServiceInfoCallback tracker = find("Living room");
        tracker.onServiceUpdated(resolved("Living room", 2, "fingerprint"));

        browser.onStartDiscoveryFailed("_fcast._tcp", NsdManager.FAILURE_INTERNAL_ERROR);

        assertTrue(devices.isEmpty());
        assertTrue(trackers.isEmpty());
        assertEquals(1, errors.size());
        tracker.onServiceUpdated(resolved("Living room", 3, "late-fingerprint"));
        assertTrue(devices.isEmpty());
    }

    private NsdManager.ServiceInfoCallback find(final String name) {
        browser.onServiceFound(service(name));
        return trackers.get(name);
    }

    private static NsdServiceInfo service(final String name) {
        final NsdServiceInfo service = mock(NsdServiceInfo.class);
        when(service.getServiceName()).thenReturn(name);
        when(service.getServiceType()).thenReturn("_fcast._tcp.");
        return service;
    }

    private static NsdServiceInfo resolved(final String name, final int host,
                                          final String fingerprint) throws Exception {
        final NsdServiceInfo service = service(name);
        when(service.getHostAddresses()).thenReturn(List.of(
                InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, (byte) host})));
        when(service.getPort()).thenReturn(46899);
        when(service.getAttributes()).thenReturn(Map.of(
                "fp", fingerprint.getBytes(StandardCharsets.UTF_8)));
        return service;
    }

    private void assertDevice(final String name, final String host, final String fingerprint)
            throws Exception {
        assertEquals(1, devices.size());
        final FCastDevice device = devices.get(0);
        assertEquals(name, device.getName());
        assertEquals(host, device.getHost());
        assertEquals(46899, device.getPort());
        final Field info = FCastDevice.class.getDeclaredField("info");
        info.setAccessible(true);
        assertEquals(fingerprint, ((DeviceInfo) info.get(device)).getTxtRecords().get("fp"));
    }
}
