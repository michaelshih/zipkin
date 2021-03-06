/*
 * Copyright 2015-2018 The OpenZipkin Authors
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
package zipkin2.v1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.Span.Kind;
import zipkin2.internal.JsonCodec;
import zipkin2.internal.Nullable;
import zipkin2.internal.V1JsonSpanReader;

/**
 * Allows you to split a v1 span when necessary. This can be the case when reading merged
 * client+server spans from storage or parsing old data formats.
 *
 * <p>This type isn't thread-safe: it re-uses state to avoid re-allocations in conversion loops.
 */
public final class V1SpanConverter {
  public static V1SpanConverter create() {
    return new V1SpanConverter();
  }

  final Span.Builder first = Span.newBuilder();
  final List<Span.Builder> spans = new ArrayList<>();
  V1Annotation cs, sr, ss, cr, ms, mr, ws, wr;

  public List<Span> convert(V1Span source) {
    List<Span> out = new ArrayList<>();
    convert(source, out);
    return out;
  }

  public void convert(V1Span source, Collection<Span> sink) {
    start(source);
    // add annotations unless they are "core"
    processAnnotations(source);
    // convert binary annotations to tags and addresses
    processBinaryAnnotations(source);
    finish(sink);
  }

  void start(V1Span source) {
    first.clear();
    spans.clear();
    cs = sr = ss = cr = ms = mr = ws = wr = null;
    newBuilder(first, source);
  }

  void processAnnotations(V1Span source) {
    for (int i = 0, length = source.annotations.size(); i < length; i++) {
      V1Annotation a = source.annotations.get(i);
      Span.Builder currentSpan = forEndpoint(source, a.endpoint);
      // core annotations require an endpoint. Don't give special treatment when that's missing
      if (a.value.length() == 2 && a.endpoint != null) {
        if (a.value.equals("cs")) {
          currentSpan.kind(Kind.CLIENT);
          cs = a;
        } else if (a.value.equals("sr")) {
          currentSpan.kind(Kind.SERVER);
          sr = a;
        } else if (a.value.equals("ss")) {
          currentSpan.kind(Kind.SERVER);
          ss = a;
        } else if (a.value.equals("cr")) {
          currentSpan.kind(Kind.CLIENT);
          cr = a;
        } else if (a.value.equals("ms")) {
          currentSpan.kind(Kind.PRODUCER);
          ms = a;
        } else if (a.value.equals("mr")) {
          currentSpan.kind(Kind.CONSUMER);
          mr = a;
        } else if (a.value.equals("ws")) {
          ws = a;
        } else if (a.value.equals("wr")) {
          wr = a;
        } else {
          currentSpan.addAnnotation(a.timestamp, a.value);
        }
      } else {
        currentSpan.addAnnotation(a.timestamp, a.value);
      }
    }

    // When bridging between event and span model, you can end up missing a start annotation
    if (cs == null && endTimestampReflectsSpanDuration(cr, source)) {
      cs = V1Annotation.create(source.timestamp, "cs", cr.endpoint);
    }
    if (sr == null && endTimestampReflectsSpanDuration(ss, source)) {
      sr = V1Annotation.create(source.timestamp, "sr", ss.endpoint);
    }

    if (cs != null && sr != null) {
      // in a shared span, the client side owns span duration by annotations or explicit timestamp
      maybeTimestampDuration(source, cs, cr);

      // special-case loopback: We need to make sure on loopback there are two span2s
      Span.Builder client = forEndpoint(source, cs.endpoint);
      Span.Builder server;
      if (closeEnough(cs.endpoint, sr.endpoint)) {
        client.kind(Kind.CLIENT);
        // fork a new span for the server side
        server = newSpanBuilder(source, sr.endpoint).kind(Kind.SERVER);
      } else {
        server = forEndpoint(source, sr.endpoint);
      }

      // the server side is smaller than that, we have to read annotations to find out
      server.shared(true).timestamp(sr.timestamp);
      if (ss != null) server.duration(ss.timestamp - sr.timestamp);
      if (cr == null && source.duration == 0) client.duration(null); // one-way has no duration
    } else if (cs != null && cr != null) {
      maybeTimestampDuration(source, cs, cr);
    } else if (sr != null && ss != null) {
      maybeTimestampDuration(source, sr, ss);
    } else { // otherwise, the span is incomplete. revert special-casing
      handleIncompleteRpc(source);
    }

    // Span v1 format did not have a shared flag. By convention, span.timestamp being absent
    // implied shared. When we only see the server-side, carry this signal over.
    if (cs == null && (sr != null && source.timestamp == 0)) {
      forEndpoint(source, sr.endpoint).shared(true);
    }

    // ms and mr are not supposed to be in the same span, but in case they are..
    if (ms != null && mr != null) {
      // special-case loopback: We need to make sure on loopback there are two span2s
      Span.Builder producer = forEndpoint(source, ms.endpoint);
      Span.Builder consumer;
      if (closeEnough(ms.endpoint, mr.endpoint)) {
        producer.kind(Kind.PRODUCER);
        // fork a new span for the consumer side
        consumer = newSpanBuilder(source, mr.endpoint).kind(Kind.CONSUMER);
      } else {
        consumer = forEndpoint(source, mr.endpoint);
      }

      consumer.shared(true);
      if (wr != null) {
        consumer.timestamp(wr.timestamp).duration(mr.timestamp - wr.timestamp);
      } else {
        consumer.timestamp(mr.timestamp);
      }

      producer.timestamp(ms.timestamp).duration(ws != null ? ws.timestamp - ms.timestamp : null);
    } else if (ms != null) {
      maybeTimestampDuration(source, ms, ws);
    } else if (mr != null) {
      if (wr != null) {
        maybeTimestampDuration(source, wr, mr);
      } else {
        maybeTimestampDuration(source, mr, null);
      }
    } else {
      if (ws != null) forEndpoint(source, ws.endpoint).addAnnotation(ws.timestamp, ws.value);
      if (wr != null) forEndpoint(source, wr.endpoint).addAnnotation(wr.timestamp, wr.value);
    }
  }

  void handleIncompleteRpc(V1Span source) {
    handleIncompleteRpc(first);
    for (int i = 0, length = spans.size(); i < length; i++) {
      handleIncompleteRpc(spans.get(i));
    }
    if (source.timestamp != 0) {
      first.timestamp(source.timestamp).duration(source.duration);
    }
  }

  void handleIncompleteRpc(Span.Builder next) {
    if (Kind.CLIENT.equals(next.kind())) {
      if (cs != null) next.timestamp(cs.timestamp);
      if (cr != null) next.addAnnotation(cr.timestamp, cr.value);
    } else if (Kind.SERVER.equals(next.kind())) {
      if (sr != null) next.timestamp(sr.timestamp);
      if (ss != null) next.addAnnotation(ss.timestamp, ss.value);
    }
  }

  static boolean endTimestampReflectsSpanDuration(V1Annotation end, V1Span source) {
    return end != null
        && source.timestamp != 0
        && source.duration != 0
        && source.timestamp + source.duration == end.timestamp;
  }

  void maybeTimestampDuration(V1Span source, V1Annotation begin, @Nullable V1Annotation end) {
    Span.Builder span2 = forEndpoint(source, begin.endpoint);
    if (source.timestamp != 0 && source.duration != 0) {
      span2.timestamp(source.timestamp).duration(source.duration);
    } else {
      span2.timestamp(begin.timestamp);
      if (end != null) span2.duration(end.timestamp - begin.timestamp);
    }
  }

  void processBinaryAnnotations(V1Span source) {
    zipkin2.Endpoint ca = null, sa = null, ma = null;
    for (int i = 0, length = source.binaryAnnotations.size(); i < length; i++) {
      V1BinaryAnnotation b = source.binaryAnnotations.get(i);
      if (b.stringValue == null) {
        if ("ca".equals(b.key)) {
          ca = b.endpoint;
        } else if ("sa".equals(b.key)) {
          sa = b.endpoint;
        } else if ("ma".equals(b.key)) {
          ma = b.endpoint;
        }
        continue;
      }

      Span.Builder currentSpan = forEndpoint(source, b.endpoint);

      // don't add marker "lc" tags
      if ("lc".equals(b.key) && b.stringValue.isEmpty()) continue;
      currentSpan.putTag(b.key, b.stringValue);
    }

    // special-case when we are missing core annotations, but we have both address annotations
    if ((cs == null && sr == null) && (ca != null && sa != null)) {
      forEndpoint(source, ca).remoteEndpoint(sa);
      return;
    }

    if (sa != null) {
      if (cs != null && !closeEnough(sa, cs.endpoint)) {
        forEndpoint(source, cs.endpoint).remoteEndpoint(sa);
      } else if (cr != null && !closeEnough(sa, cr.endpoint)) {
        forEndpoint(source, cr.endpoint).remoteEndpoint(sa);
      } else if (cs == null && cr == null && sr == null && ss == null) {
        // "sa" is a default for a remote address, don't make it a client span
        forEndpoint(source, null).remoteEndpoint(sa);
      }
    }

    if (ca != null) {
      if (sr != null && !closeEnough(ca, sr.endpoint)) {
        forEndpoint(source, sr.endpoint).remoteEndpoint(ca);
      }
      if (ss != null && !closeEnough(ca, ss.endpoint)) {
        forEndpoint(source, ss.endpoint).remoteEndpoint(ca);
      } else if (cs == null && cr == null && sr == null && ss == null) { // no core annotations
        forEndpoint(source, null).kind(Kind.SERVER).remoteEndpoint(ca);
      }
    }

    if (ma != null) {
      if (ms != null && !closeEnough(ma, ms.endpoint)) {
        forEndpoint(source, ms.endpoint).remoteEndpoint(ma);
      }
      if (mr != null && !closeEnough(ma, mr.endpoint)) {
        forEndpoint(source, mr.endpoint).remoteEndpoint(ma);
      }
    }
  }

  Span.Builder forEndpoint(V1Span source, @Nullable zipkin2.Endpoint e) {
    if (e == null) return first; // allocate missing endpoint data to first span
    if (closeEnoughEndpoint(first, e)) return first;
    for (int i = 0, length = spans.size(); i < length; i++) {
      Span.Builder next = spans.get(i);
      if (closeEnoughEndpoint(next, e)) return next;
    }
    return newSpanBuilder(source, e);
  }

  static boolean closeEnoughEndpoint(Span.Builder builder, Endpoint e) {
    Endpoint localEndpoint = builder.localEndpoint();
    if (localEndpoint == null) {
      builder.localEndpoint(e);
      return true;
    } else return closeEnough(localEndpoint, e);
  }

  Span.Builder newSpanBuilder(V1Span source, Endpoint e) {
    Span.Builder result = newBuilder(Span.newBuilder(), source).localEndpoint(e);
    spans.add(result);
    return result;
  }

  void finish(Collection<Span> sink) {
    sink.add(first.build());
    for (int i = 0, length = spans.size(); i < length; i++) {
      sink.add(spans.get(i).build());
    }
  }

  static boolean closeEnough(Endpoint left, Endpoint right) {
    return equal(left.serviceName(), right.serviceName());
  }

  static boolean equal(Object a, Object b) {
    return a == b || (a != null && a.equals(b));
  }

  static Span.Builder newBuilder(Span.Builder builder, V1Span source) {
    return builder
        .traceId(source.traceIdHigh, source.traceId)
        .parentId(source.parentId)
        .id(source.id)
        .name(source.name)
        .debug(source.debug);
  }

  V1SpanConverter() {}
}
