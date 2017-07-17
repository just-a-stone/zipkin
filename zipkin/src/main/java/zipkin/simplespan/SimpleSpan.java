/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.simplespan;

import com.google.auto.value.AutoValue;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import zipkin.Annotation;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.TraceKeys;
import zipkin.internal.Nullable;

import static zipkin.internal.Util.UTF_8;
import static zipkin.internal.Util.checkNotNull;
import static zipkin.internal.Util.lowerHexToUnsignedLong;
import static zipkin.internal.Util.sortedList;

/**
 * This is a single-host view of a {@link Span}: the primary way tracers record data.
 *
 * <p>This type is intended to replace use of {@link Span} in tracers, as it represents a single-
 * host view of an operation. By making one endpoint implicit for all data, this type does not need
 * to repeat endpoints on each data like {@link Span span} does. This results in simpler and smaller
 * data.
 */
@AutoValue
public abstract class SimpleSpan implements Serializable { // for Spark jobs
  private static final long serialVersionUID = 0L;

  /** When non-zero, the trace containing this span uses 128-bit trace identifiers. */
  public abstract long traceIdHigh();

  /** Unique 8-byte identifier for a trace, set on all spans within it. */
  public abstract long traceId();

  /** The parent's {@link #id} or null if this the root span in a trace. */
  @Nullable public abstract Long parentId();

  /**
   * Unique 8-byte identifier of this span within a trace.
   *
   * <p>A span is uniquely identified in storage by ({@linkplain #traceId}, {@linkplain #id()}).
   */
  public abstract long id();

  /** Indicates the primary span type. */
  public enum Kind {
    CLIENT,
    SERVER
  }

  /** When present, used to interpret {@link #remoteEndpoint} */
  @Nullable public abstract Kind kind();

  /**
   * Span name in lowercase, rpc method for example.
   *
   * <p>Conventionally, when the span name isn't known, name = "unknown".
   */
  @Nullable public abstract String name();

  /**
   * Epoch microseconds of the start of this span, possibly absent if this an incomplete span.
   *
   * <p>This value should be set directly by instrumentation, using the most precise value possible.
   * For example, {@code gettimeofday} or multiplying {@link System#currentTimeMillis} by 1000.
   *
   * <p>There are three known edge-cases where this could be reported absent:
   *
   * <pre><ul>
   * <li>A span was allocated but never started (ex not yet received a timestamp)</li>
   * <li>The span's start event was lost</li>
   * <li>Data about a completed span (ex tags) were sent after the fact</li>
   * </pre><ul>
   *
   * @see #finishTimestamp()
   */
  @Nullable public abstract Long startTimestamp();

  /**
   * Epoch microseconds of the completion of this span, possibly absent if this a span is in-flight
   * or incomplete. The critical path latency in microseconds of this operation is: {@code
   * finishTimestamp - startTimestamp}.
   *
   * <p>Since this type represents a span within a single host, care should be taken to not report
   * an inaccurate timestamp. Typically, this is accomplished by using an offset from {@link
   * #startTimestamp}, as this avoids problems of clocks, such as skew or NTP updates causing time
   * to move backwards.
   *
   * @see #startTimestamp
   */
  @Nullable public abstract Long finishTimestamp();

  /**
   * The host that recorded this span, primarily for query by service name.
   *
   * <p>Instrumentation should always record this and be consistent as possible with the service
   * name as it is used in search. This is nullable for legacy reasons.
   */
  // Nullable for data conversion especially late arriving data which might not have an annotation
  @Nullable public abstract Endpoint localEndpoint();

  /** When an RPC (or messaging) span, indicates the other side of the connection. */
  @Nullable public abstract Endpoint remoteEndpoint();

  /**
   * Events that explain latency with a timestamp. Unlike log statements, annotations are often
   * short or contain codes: for example "brave.flush". Annotations are sorted ascending by
   * timestamp.
   */
  public abstract List<Annotation> annotations();

  /**
   * Tags a span with context, usually to support query or aggregation.
   *
   * <p>example, a binary annotation key could be {@link TraceKeys#HTTP_PATH "http.path"}.
   */
  public abstract Map<String, String> tags();

  /** True is a request to store this span even if it overrides sampling policy. */
  @Nullable public abstract Boolean debug();

  /**
   * True if we are contributing to a span started by another tracer (ex on a different host).
   * Defaults to null. When set, it is expected for {@link #kind()} to be {@link Kind#SERVER}.
   *
   * <p>When an RPC trace is client-originated, it will be sampled and the same span ID is used for
   * the server side. However, the server shouldn't set span.timestamp or duration since it didn't
   * start the span.
   */
  @Nullable public abstract Boolean shared();

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public static final class Builder {
    Long traceId;
    long traceIdHigh;
    Long parentId;
    Long id;
    Kind kind;
    String name;
    Long startTimestamp;
    Long finishTimestamp;
    Endpoint localEndpoint;
    Endpoint remoteEndpoint;
    ArrayList<Annotation> annotations;
    TreeMap<String, String> tags;
    Boolean debug;
    Boolean shared;

    Builder() {
    }

    public Builder clear() {
      traceIdHigh = 0L;
      traceId = null;
      parentId = null;
      id = null;
      kind = null;
      name = null;
      startTimestamp = null;
      finishTimestamp = null;
      localEndpoint = null;
      remoteEndpoint = null;
      if (annotations != null) annotations.clear();
      if (tags != null) tags.clear();
      debug = null;
      shared = null;
      return this;
    }

    @Override public Builder clone() {
      Builder result = new Builder();
      result.traceIdHigh = traceIdHigh;
      result.traceId = traceId;
      result.parentId = parentId;
      result.id = id;
      result.kind = kind;
      result.name = name;
      result.startTimestamp = startTimestamp;
      result.finishTimestamp = finishTimestamp;
      result.localEndpoint = localEndpoint;
      result.remoteEndpoint = remoteEndpoint;
      if (annotations != null) {
        result.annotations = (ArrayList) annotations.clone();
      }
      if (tags != null) {
        result.tags = (TreeMap) tags.clone();
      }
      result.debug = debug;
      result.shared = shared;
      return result;
    }

    Builder(SimpleSpan source) {
      traceId = source.traceId();
      parentId = source.parentId();
      id = source.id();
      kind = source.kind();
      name = source.name();
      startTimestamp = source.startTimestamp();
      finishTimestamp = source.finishTimestamp();
      localEndpoint = source.localEndpoint();
      remoteEndpoint = source.remoteEndpoint();
      if (!source.annotations().isEmpty()) {
        annotations = new ArrayList<>(source.annotations().size());
        annotations.addAll(source.annotations());
      }
      if (!source.tags().isEmpty()) {
        tags = new TreeMap<>();
        tags.putAll(source.tags());
      }
      debug = source.debug();
      shared = source.shared();
    }

    /**
     * Decodes the trace ID from its lower-hex representation.
     *
     * <p>Use this instead decoding yourself and calling {@link #traceIdHigh(long)} and {@link
     * #traceId(long)}
     */
    public Builder traceId(String traceId) {
      checkNotNull(traceId, "traceId");
      if (traceId.length() == 32) {
        traceIdHigh(lowerHexToUnsignedLong(traceId, 0));
      }
      return traceId(lowerHexToUnsignedLong(traceId));
    }

    /** @see SimpleSpan#traceIdHigh */
    public Builder traceIdHigh(long traceIdHigh) {
      this.traceIdHigh = traceIdHigh;
      return this;
    }

    /** @see SimpleSpan#traceId */
    public Builder traceId(long traceId) {
      this.traceId = traceId;
      return this;
    }

    /**
     * Decodes the parent ID from its lower-hex representation.
     *
     * <p>Use this instead decoding yourself and calling {@link #parentId(Long)}
     */
    public Builder parentId(@Nullable String parentId) {
      this.parentId = parentId != null ? lowerHexToUnsignedLong(parentId) : null;
      return this;
    }

    /** @see SimpleSpan#parentId */
    public Builder parentId(@Nullable Long parentId) {
      this.parentId = parentId;
      return this;
    }

    /**
     * Decodes the span ID from its lower-hex representation.
     *
     * <p>Use this instead decoding yourself and calling {@link #id(long)}
     */
    public Builder id(String id) {
      this.id = lowerHexToUnsignedLong(id);
      return this;
    }

    /** @see SimpleSpan#id */
    public Builder id(long id) {
      this.id = id;
      return this;
    }

    /** @see SimpleSpan#kind */
    public Builder kind(@Nullable Kind kind) {
      this.kind = kind;
      return this;
    }

    /** @see SimpleSpan#name */
    public Builder name(@Nullable String name) {
      this.name = name == null || name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
      return this;
    }

    /** @see SimpleSpan#startTimestamp */
    public Builder startTimestamp(@Nullable Long startTimestamp) {
      if (startTimestamp != null && startTimestamp == 0L) startTimestamp = null;
      this.startTimestamp = startTimestamp;
      return this;
    }

    /** @see SimpleSpan#finishTimestamp */
    public Builder finishTimestamp(@Nullable Long finishTimestamp) {
      if (finishTimestamp != null && finishTimestamp == 0L) finishTimestamp = null;
      this.finishTimestamp = finishTimestamp;
      return this;
    }

    /** @see SimpleSpan#localEndpoint */
    public Builder localEndpoint(@Nullable Endpoint localEndpoint) {
      this.localEndpoint = localEndpoint;
      return this;
    }

    /** @see SimpleSpan#remoteEndpoint */
    public Builder remoteEndpoint(@Nullable Endpoint remoteEndpoint) {
      this.remoteEndpoint = remoteEndpoint;
      return this;
    }

    /** @see SimpleSpan#annotations */
    public Builder addAnnotation(long timestamp, String value) {
      if (annotations == null) annotations = new ArrayList<>(2);
      annotations.add(Annotation.create(timestamp, value, null));
      return this;
    }

    /** @see SimpleSpan#tags */
    public Builder putTag(String key, String value) {
      if (tags == null) tags = new TreeMap<>();
      this.tags.put(checkNotNull(key, "key"), checkNotNull(value, "value"));
      return this;
    }

    /** @see SimpleSpan#debug */
    public Builder debug(@Nullable Boolean debug) {
      this.debug = debug;
      return this;
    }

    /** @see SimpleSpan#shared */
    public Builder shared(@Nullable Boolean shared) {
      this.shared = shared;
      return this;
    }

    public SimpleSpan build() {
      return new AutoValue_SimpleSpan(
        traceIdHigh,
        traceId,
        parentId,
        id,
        kind,
        name,
        startTimestamp,
        finishTimestamp,
        localEndpoint,
        remoteEndpoint,
        sortedList(annotations),
        tags == null ? Collections.emptyMap() : new LinkedHashMap<String, String>(tags),
        debug,
        shared
      );
    }
  }

  @Override
  public String toString() {
    return new String(SimpleSpanCodec.JSON.writeSpan(this), UTF_8);
  }

  // Since this is an immutable object, and we have thrift handy, defer to a serialization proxy.
  final Object writeReplace() throws ObjectStreamException {
    return new SerializedForm(SimpleSpanCodec.JSON.writeSpan(this));
  }

  static final class SerializedForm implements Serializable {
    private static final long serialVersionUID = 0L;

    private final byte[] bytes;

    SerializedForm(byte[] bytes) {
      this.bytes = bytes;
    }

    Object readResolve() throws ObjectStreamException {
      try {
        return SimpleSpanCodec.JSON.readSpan(bytes);
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
        throw new StreamCorruptedException(e.getMessage());
      }
    }
  }
}
