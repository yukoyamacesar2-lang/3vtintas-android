package br.com.tresvtintas.mobile.platform.location;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationCompat;
import br.com.tresvtintas.mobile.core.location.AdaptiveLocationSampler;
import br.com.tresvtintas.mobile.core.location.LocationRepository;
import br.com.tresvtintas.mobile.core.location.LocationSchedule;
import br.com.tresvtintas.mobile.core.location.WorkforceLocationHost;
import br.com.tresvtintas.mobile.core.network.dto.LocationDtos;
import br.com.tresvtintas.mobile.data.location.RemoteLocationRepository;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorkforceLocationService extends Service {
    private static final String CHANNEL_ID = "3v_workforce_location";
    private static final int NOTIFICATION_ID = 3107;
    private static final long RECONCILE_SECONDS = 60L;
    private static final long POLICY_REFRESH_SECONDS = 60L;
    private static final long DEFAULT_INTERVAL_SECONDS = 120L;
    private static final long DEFAULT_BATCH_SECONDS = 300L;
    private static final int MAXIMUM_LOCAL_BATCH_POINTS = 10;
    private static final String ACTIVE_REPORT_STATE = "active";
    private final AtomicInteger sequence = new AtomicInteger();
    private final AdaptiveLocationSampler sampler = new AdaptiveLocationSampler();
    private final LocationCallback callback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult result) {
            for (Location location : result.getLocations()) {
                worker.execute(() -> persistAndUpload(location));
            }
        }
    };
    private ScheduledExecutorService worker;
    private FusedLocationProviderClient fusedClient;
    private EncryptedLocationBuffer buffer;
    private WorkforceLocationHost host;
    private LocationRepository repository;
    private long repositoryOrganizationId = -1L;
    private Optional<LocationDtos.Policy> policy = Optional.empty();
    private boolean requestingUpdates;
    private long lastPolicyRefresh;
    private long lastHeartbeat;
    private long batchStartedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "3v-workforce-location");
            thread.setDaemon(true);
            return thread;
        });
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        buffer = new EncryptedLocationBuffer(this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Preparando rastreamento seguro"));
        LocationTrackingController.publishState(this, "starting");
        worker.scheduleWithFixedDelay(
                this::reconcile,
                0L,
                RECONCILE_SECONDS,
                TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        worker.shutdownNow();
        LocationTrackingController.publishState(this, "stopped");
        super.onDestroy();
    }

    private void reconcile() {
        if (!LocationTrackingController.enabled(this)) {
            stopSelf();
            return;
        }
        if (!prepareRuntime()) {
            blocked("Aguardando sessão e loja autorizadas");
            return;
        }
        if (!LocationPermissions.hasPrecise(this)
                || !LocationPermissions.hasBackground(this)
                || !LocationPermissions.hasNotifications(this)
                || !LocationPermissions.locationEnabled(this)) {
            stopLocationUpdates();
            blocked("Permissão ou localização do aparelho indisponível");
            sendHeartbeat("blocked");
            return;
        }
        refreshPolicyIfRequired();
        if (policy.isEmpty()) {
            blocked("Aguardando política da loja");
            return;
        }
        if (!LocationSchedule.active(policy.orElseThrow(), Instant.now())) {
            stopLocationUpdates();
            sampler.reset();
            flushBuffer(true, "paused_outside_schedule");
            String message = policy.orElseThrow().enabled()
                    ? "Coleta pausada fora da jornada"
                    : "Rastreamento desativado pelo painel";
            publish("paused_outside_schedule", message);
            sendHeartbeat("paused_outside_schedule");
            return;
        }
        startLocationUpdates();
        publish(ACTIVE_REPORT_STATE, "Rastreamento ativo pela jornada do painel");
        sendHeartbeat(ACTIVE_REPORT_STATE);
        flushBuffer(false, ACTIVE_REPORT_STATE);
    }

    private boolean prepareRuntime() {
        if (!(getApplication() instanceof WorkforceLocationHost currentHost)
                || !currentHost.isWorkforceLocationBuild()
                || currentHost.workforceLocationApi().isEmpty()
                || currentHost.workforceLocationOrganizationId().isEmpty()) {
            return false;
        }
        long organizationId = currentHost.workforceLocationOrganizationId().getAsLong();
        if (host != currentHost
                || repository == null
                || repositoryOrganizationId != organizationId) {
            host = currentHost;
            repository = new RemoteLocationRepository(
                    organizationId,
                    currentHost.workforceLocationApi().orElseThrow());
            repositoryOrganizationId = organizationId;
            policy = Optional.empty();
            lastPolicyRefresh = 0L;
            lastHeartbeat = 0L;
        }
        return true;
    }

    private void refreshPolicyIfRequired() {
        long now = System.currentTimeMillis();
        if (policy.isPresent()
                && now - lastPolicyRefresh < TimeUnit.SECONDS.toMillis(POLICY_REFRESH_SECONDS)) {
            return;
        }
        try {
            LocationDtos.PolicyResponse response = repository.policy();
            if (response.consent() == null
                    || !"accepted".equals(response.consent().status())) {
                stopLocationUpdates();
                policy = Optional.empty();
                publish("blocked", "Consentimento de localização necessário");
                return;
            }
            policy = Optional.of(response.policy());
            lastPolicyRefresh = now;
        } catch (IOException ignored) {
            // Keep the last valid policy and retry without dropping encrypted buffered points.
        }
    }

    private void startLocationUpdates() {
        if (requestingUpdates) {
            return;
        }
        long interval = policy
                .map(LocationDtos.Policy::locationIntervalSeconds)
                .map(Integer::longValue)
                .orElse(DEFAULT_INTERVAL_SECONDS);
        long maximumDelay = policy
                .map(LocationDtos.Policy::maxBatchDelaySeconds)
                .map(Integer::longValue)
                .orElse(DEFAULT_BATCH_SECONDS);
        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                TimeUnit.SECONDS.toMillis(interval))
                .setMinUpdateIntervalMillis(TimeUnit.SECONDS.toMillis(Math.max(30L, interval / 2L)))
                .setMaxUpdateDelayMillis(TimeUnit.SECONDS.toMillis(maximumDelay))
                .build();
        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper());
            requestingUpdates = true;
        } catch (SecurityException exception) {
            blocked("Permissão de localização removida");
        }
    }

    private void stopLocationUpdates() {
        if (!requestingUpdates || fusedClient == null) {
            return;
        }
        fusedClient.removeLocationUpdates(callback);
        requestingUpdates = false;
    }

    private void persistAndUpload(Location location) {
        Instant recordedAt = Instant.ofEpochMilli(location.getTime());
        if (policy.isEmpty()
                || !LocationSchedule.active(policy.orElseThrow(), recordedAt)) {
            return;
        }
        Duration configuredInterval = Duration.ofSeconds(policy
                .map(LocationDtos.Policy::locationIntervalSeconds)
                .orElse((int) DEFAULT_INTERVAL_SECONDS));
        AdaptiveLocationSampler.Sample sample = new AdaptiveLocationSampler.Sample(
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracy(),
                location.hasSpeed() ? location.getSpeed() : null,
                recordedAt);
        if (!sampler.shouldAccept(sample, configuredInterval)) {
            return;
        }
        LocationDtos.Point point = new LocationDtos.Point(
                Math.floorMod(sequence.getAndIncrement(), 1_000_001),
                coordinateE7(location.getLatitude()),
                coordinateE7(location.getLongitude()),
                Math.min(10_000, Math.max(0, Math.round(location.getAccuracy()))),
                location.hasSpeed()
                        ? Math.min(200_000, Math.max(0, Math.round(location.getSpeed() * 1_000F)))
                        : null,
                location.hasBearing()
                        ? Math.floorMod(Math.round(location.getBearing()), 360)
                        : null,
                location.hasAltitude()
                        ? Math.min(2_000_000, Math.max(-100_000,
                                (int) Math.round(location.getAltitude() * 100D)))
                        : null,
                BatteryState.percent(this),
                LocationCompat.isMock(location),
                recordedAt.toString());
        try {
            boolean startsBatch = buffer.size() == 0;
            buffer.append(point);
            if (startsBatch) {
                batchStartedAt = System.currentTimeMillis();
            }
            flushBuffer(buffer.size() >= MAXIMUM_LOCAL_BATCH_POINTS, ACTIVE_REPORT_STATE);
        } catch (IOException ignored) {
            blocked("Buffer protegido temporariamente indisponível");
        }
    }

    private void flushBuffer(boolean force, String reportState) {
        if (repository == null || host == null) {
            return;
        }
        try {
            int buffered = buffer.size();
            if (buffered == 0) {
                batchStartedAt = 0L;
                return;
            }
            long now = System.currentTimeMillis();
            long maximumDelay = policy
                    .map(LocationDtos.Policy::maxBatchDelaySeconds)
                    .map(value -> TimeUnit.SECONDS.toMillis(value))
                    .orElse(TimeUnit.SECONDS.toMillis(DEFAULT_BATCH_SECONDS));
            boolean recoveredBatch = batchStartedAt == 0L;
            if (!force && !recoveredBatch && now - batchStartedAt < maximumDelay) {
                return;
            }
            Optional<EncryptedLocationBuffer.Batch> pending = buffer.pendingBatch();
            while (pending.isPresent()) {
                EncryptedLocationBuffer.Batch batch = pending.orElseThrow();
                repository.send(
                        batch.key(),
                        batch.points(),
                        LocationPermissions.report(this, host, reportState));
                buffer.acknowledge(batch);
                pending = buffer.pendingBatch();
            }
            batchStartedAt = 0L;
        } catch (IOException ignored) {
            // Retry keeps the same encrypted batch key, preventing duplicate points after ambiguity.
        }
    }

    private void sendHeartbeat(String state) {
        if (repository == null || host == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = policy
                .map(LocationDtos.Policy::heartbeatSeconds)
                .map(value -> TimeUnit.SECONDS.toMillis(value))
                .orElse(TimeUnit.MINUTES.toMillis(5L));
        if (now - lastHeartbeat < interval) {
            return;
        }
        try {
            repository.heartbeat(LocationPermissions.report(this, host, state));
            lastHeartbeat = now;
        } catch (IOException ignored) {
            // A later reconciliation retries the operational heartbeat.
        }
    }

    private void blocked(String message) {
        publish("blocked", message);
    }

    private void publish(String state, String message) {
        LocationTrackingController.publishState(this, state);
        NotificationManager manager = ContextCompat.getSystemService(
                this,
                NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification(message));
        }
    }

    private Notification notification(String message) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = launch == null
                ? null
                : PendingIntent.getActivity(
                        this,
                        0,
                        launch,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("3V Workforce")
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        NotificationManager manager = ContextCompat.getSystemService(
                this,
                NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Localização em horário comercial",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                "Mantém visível o rastreamento autorizado de vendedores e entregadores.");
        manager.createNotificationChannel(channel);
    }

    private static int coordinateE7(double coordinate) {
        return (int) Math.round(coordinate * 10_000_000D);
    }
}
