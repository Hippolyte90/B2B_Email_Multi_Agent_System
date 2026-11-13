package fr.paybridge.mas.adapters.outbound.persistence;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.draft.Draft;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/** Sérialisation limitée aux records validés et aux instantanés du domaine, sans Unsafe. */
final class StorageJson {
    private final Gson gson = new GsonBuilder().disableJdkUnsafe().setStrictness(Strictness.STRICT)
            .registerTypeAdapter(Instant.class, scalar(Instant::parse))
            .registerTypeAdapter(OffsetDateTime.class, scalar(OffsetDateTime::parse))
            .registerTypeAdapter(Duration.class, scalar(Duration::parse))
            .registerTypeAdapter(OptionalLong.class, new TypeAdapter<OptionalLong>() {
                @Override public void write(JsonWriter out, OptionalLong value) throws IOException {
                    if (value.isPresent()) { out.value(value.getAsLong()); } else { out.nullValue(); }
                }
                @Override public OptionalLong read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) { in.nextNull(); return OptionalLong.empty(); }
                    return OptionalLong.of(in.nextLong());
                }
            })
            .registerTypeAdapterFactory(new TypeAdapterFactory() {
                @Override public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                    if (type.getRawType() != Optional.class) { return null; }
                    var parameter = ((ParameterizedType) type.getType()).getActualTypeArguments()[0];
                    var delegate = gson.getAdapter(TypeToken.get(parameter));
                    @SuppressWarnings("unchecked") var adapter = (TypeAdapter<T>) new TypeAdapter<Optional<?>>() {
                        @Override public void write(JsonWriter out, Optional<?> value) throws IOException {
                            if (value.isEmpty()) { out.nullValue(); }
                            else {
                                @SuppressWarnings("unchecked") var writer = (TypeAdapter<Object>) delegate;
                                writer.write(out, value.get());
                            }
                        }
                        @Override public Optional<?> read(JsonReader in) throws IOException {
                            if (in.peek() == JsonToken.NULL) { in.nextNull(); return Optional.empty(); }
                            return Optional.of(delegate.read(in));
                        }
                    };
                    return adapter;
                }
            }).serializeNulls().create();

    private static <T> TypeAdapter<T> scalar(Function<String, T> parse) {
        return new TypeAdapter<>() {
            @Override public void write(JsonWriter out, T value) throws IOException { out.value(value.toString()); }
            @Override public T read(JsonReader in) throws IOException { return parse.apply(in.nextString()); }
        };
    }

    String encode(Object value) { return gson.toJson(value); }

    <T> T decode(String payload, Class<T> type) {
        try {
            var value = gson.fromJson(payload, type);
            if (value == null) { throw new IllegalArgumentException(); }
            return value;
        } catch (RuntimeException failure) {
            throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA);
        }
    }

    Conversation conversation(String payload) {
        try { return Conversation.restore(decode(payload, Conversation.Snapshot.class)); }
        catch (RuntimeException failure) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
    }
    Draft draft(String payload) {
        try { return Draft.restore(decode(payload, Draft.Snapshot.class)); }
        catch (RuntimeException failure) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
    }
}
