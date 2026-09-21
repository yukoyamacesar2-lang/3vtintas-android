package br.com.tresvtintas.mobile.platform.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import br.com.tresvtintas.mobile.core.network.dto.LocationDtos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class EncryptedLocationBuffer {
    record Batch(String key, List<LocationDtos.Point> points) {
        Batch {
            points = List.copyOf(points);
        }
    }

    private record State(String pendingKey, List<LocationDtos.Point> points) {
        State {
            points = List.copyOf(points);
        }
    }

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "3v_workforce_location_buffer_v1";
    private static final String PREFERENCES = "3v_workforce_location_buffer";
    private static final String PAYLOAD = "ciphertext";
    private static final int FORMAT_VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    // Keep a bounded encrypted outbox, but never silently evict old points.
    // At the configured 120-second sampling interval this retains several
    // days of offline operation. A full queue is surfaced as a blocked state
    // so the operator can restore connectivity instead of losing history.
    private static final int MAXIMUM_POINTS = 2_000;
    private static final int MAXIMUM_BATCH_POINTS = 50;
    private final SharedPreferences preferences;

    EncryptedLocationBuffer(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    synchronized void append(LocationDtos.Point point) throws IOException {
        State state = read();
        List<LocationDtos.Point> next = new ArrayList<>(state.points());
        if (next.size() >= MAXIMUM_POINTS) {
            throw new IOException("Protected location buffer is full.");
        }
        next.add(point);
        write(new State(state.pendingKey(), next));
    }

    synchronized int size() throws IOException {
        return read().points().size();
    }

    synchronized Optional<Batch> pendingBatch() throws IOException {
        State state = read();
        if (state.points().isEmpty()) {
            return Optional.empty();
        }
        String key = state.pendingKey().isBlank()
                ? UUID.randomUUID().toString()
                : state.pendingKey();
        if (!key.equals(state.pendingKey())) {
            write(new State(key, state.points()));
        }
        int end = Math.min(MAXIMUM_BATCH_POINTS, state.points().size());
        return Optional.of(new Batch(key, state.points().subList(0, end)));
    }

    synchronized void acknowledge(Batch batch) throws IOException {
        State state = read();
        if (!state.pendingKey().equals(batch.key())) {
            return;
        }
        int removed = Math.min(batch.points().size(), state.points().size());
        List<LocationDtos.Point> remaining = new ArrayList<>(
                state.points().subList(removed, state.points().size()));
        write(new State("", remaining));
    }

    private State read() throws IOException {
        String encoded = preferences.getString(PAYLOAD, "");
        if (encoded == null || encoded.isBlank()) {
            return new State("", List.of());
        }
        try {
            byte[] envelope = Base64.decode(encoded, Base64.NO_WRAP);
            DataInputStream header = new DataInputStream(new ByteArrayInputStream(envelope));
            int nonceLength = header.readUnsignedByte();
            int ciphertextLength = envelope.length - Byte.BYTES - nonceLength;
            if (nonceLength <= 0 || ciphertextLength <= 0) {
                throw new IOException("Invalid protected location buffer envelope.");
            }
            byte[] nonce = new byte[nonceLength];
            header.readFully(nonce);
            byte[] ciphertext = new byte[ciphertextLength];
            header.readFully(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return deserialize(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            preferences.edit().remove(PAYLOAD).apply();
            throw new IOException("Protected location buffer is unavailable.", exception);
        }
    }

    private void write(State state) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ciphertext = cipher.doFinal(serialize(state));
            byte[] nonce = cipher.getIV();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(nonce.length);
                output.write(nonce);
                output.write(ciphertext);
            }
            preferences.edit()
                    .putString(PAYLOAD, Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    .apply();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Protected location buffer cannot be written.", exception);
        }
    }

    private static byte[] serialize(State state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT_VERSION);
            output.writeUTF(state.pendingKey());
            output.writeInt(state.points().size());
            for (LocationDtos.Point point : state.points()) {
                output.writeInt(point.sequence());
                output.writeInt(point.latitudeE7());
                output.writeInt(point.longitudeE7());
                output.writeInt(point.accuracyMeters());
                writeNullableInt(output, point.speedMillimetersPerSecond());
                writeNullableInt(output, point.bearingDegrees());
                writeNullableInt(output, point.altitudeCentimeters());
                writeNullableInt(output, point.batteryPercent());
                output.writeBoolean(point.mocked());
                output.writeUTF(point.recordedAt());
            }
        }
        return bytes.toByteArray();
    }

    private static State deserialize(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported protected location buffer.");
            }
            String pendingKey = input.readUTF();
            int count = input.readInt();
            if (count < 0 || count > MAXIMUM_POINTS) {
                throw new IOException("Invalid protected location buffer size.");
            }
            List<LocationDtos.Point> points = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                points.add(new LocationDtos.Point(
                        input.readInt(),
                        input.readInt(),
                        input.readInt(),
                        input.readInt(),
                        readNullableInt(input),
                        readNullableInt(input),
                        readNullableInt(input),
                        readNullableInt(input),
                        input.readBoolean(),
                        input.readUTF()));
            }
            return new State(pendingKey, points);
        }
    }

    private static void writeNullableInt(DataOutputStream output, Integer value)
            throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeInt(value);
        }
    }

    private static Integer readNullableInt(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readInt() : null;
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey secretKey) {
            return secretKey;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
