/**
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.informantproject.local.trace;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.JsonCharSequence;
import org.informantproject.api.LargeStringBuilder;
import org.informantproject.api.SpanContextMap;
import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.ImmutableCoreConfiguration;
import org.informantproject.core.stack.MergedStackTreeNode;
import org.informantproject.core.trace.MetricDataItem;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceSink;
import org.informantproject.core.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Implementation of TraceSink for local storage in embedded H2 database. Some day there may be
 * another implementation for remote storage (e.g. central monitoring system).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceSinkLocal implements TraceSink {

    private static final Logger logger = LoggerFactory.getLogger(TraceSinkLocal.class);

    private final ExecutorService executorService = DaemonExecutors
            .newSingleThreadExecutor("Informant-StackCollector");

    private final ConfigurationService configurationService;
    private final TraceDao traceDao;
    private final StackTraceDao stackTraceDao;

    private final AtomicInteger queueLength = new AtomicInteger(0);

    @Inject
    public TraceSinkLocal(ConfigurationService configurationService, TraceDao traceDao,
            StackTraceDao stackTraceDao) {

        this.configurationService = configurationService;
        this.traceDao = traceDao;
        this.stackTraceDao = stackTraceDao;
    }

    public void onCompletedTrace(final Trace trace) {
        ImmutableCoreConfiguration configuration = configurationService.getCoreConfiguration();
        int thresholdMillis = configuration.getThresholdMillis();
        boolean thresholdDisabled =
                (thresholdMillis == ImmutableCoreConfiguration.THRESHOLD_DISABLED);
        long durationInNanoseconds = trace.getRootSpan().getDuration();
        // if the completed trace exceeded the given threshold then it is sent to the sink. the
        // completed trace is also checked in case it was previously sent to the sink and marked as
        // stuck, and the threshold was disabled or increased in the meantime, in which case the
        // full completed trace needs to be (re-)sent to the sink
        if ((!thresholdDisabled && durationInNanoseconds >= TimeUnit.MILLISECONDS
                .toNanos(thresholdMillis)) || trace.isStuck()) {

            queueLength.incrementAndGet();
            executorService.execute(new Runnable() {
                public void run() {
                    traceDao.storeTrace(buildStoredTrace(trace));
                    queueLength.decrementAndGet();
                }
            });
        }
    }

    public void onStuckTrace(Trace trace) {
        traceDao.storeTrace(buildStoredTrace(trace));
    }

    public int getQueueLength() {
        return queueLength.get();
    }

    public StoredTrace buildStoredTrace(Trace trace) {
        StoredTrace storedTrace = new StoredTrace();
        storedTrace.setId(trace.getId());
        storedTrace.setStartAt(trace.getStartDate().getTime());
        storedTrace.setStuck(trace.isStuck() && !trace.isCompleted());
        storedTrace.setDuration(trace.getDuration());
        storedTrace.setCompleted(trace.isCompleted());
        Span rootSpan = trace.getRootSpan().getSpans().iterator().next();
        storedTrace.setDescription(rootSpan.getDescription().toString());
        storedTrace.setUsername(trace.getUsername());
        Gson gson = new Gson();
        storedTrace.setMetrics(getMetricsJson(trace, gson));
        storedTrace.setContextMap(getContextMapJson(trace, gson));
        Map<String, String> stackTraces = new HashMap<String, String>();
        storedTrace.setSpans(getSpansJson(trace, stackTraces, gson));
        stackTraceDao.storeStackTraces(stackTraces);
        storedTrace.setMergedStackTree(getMergedStackTreeJson(trace));
        return storedTrace;
    }

    public void shutdown() {
        logger.debug("shutdown()");
        executorService.shutdownNow();
    }

    public static String getMetricsJson(Trace trace, Gson gson) {
        List<MetricDataItem> items = Lists.newArrayList(trace.getMetricData().getItems());
        if (items.size() == 0) {
            return null;
        } else {
            Collections.sort(items, new Comparator<MetricDataItem>() {
                public int compare(MetricDataItem item1, MetricDataItem item2) {
                    // can't just subtract totals and cast to int because of int overflow
                    return item1.getTotal() >= item2.getTotal() ? -1 : 1;
                }
            });
            return gson.toJson(items);
        }
    }

    public static String getContextMapJson(Trace trace, Gson gson) {
        Span rootSpan = trace.getRootSpan().getSpans().iterator().next();
        // Span.getContextMap() may be creating the context map on the fly, so don't call it twice
        SpanContextMap contextMap = rootSpan.getContextMap();
        if (contextMap == null) {
            return null;
        } else {
            return gson.toJson(contextMap, new TypeToken<Map<String, Object>>() {}.getType());
        }
    }

    public static CharSequence getSpansJson(Trace trace, Map<String, String> stackTraces,
            Gson gson) {

        try {
            LargeStringBuilder sb = new LargeStringBuilder();
            JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
            jw.beginArray();
            boolean skipContextMap = true;
            for (Span span : trace.getRootSpan().getSpans()) {
                writeSpan(span, stackTraces, jw, sb, gson, skipContextMap);
                skipContextMap = false;
            }
            jw.endArray();
            jw.close();
            return sb.build();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    public static CharSequence getMergedStackTreeJson(Trace trace) {
        if (trace.getMergedStackTree().getRootNode() == null) {
            return null;
        }
        try {
            MergedStackTreeNode rootNode = trace.getMergedStackTree().getRootNode();
            LargeStringBuilder sb = new LargeStringBuilder();
            JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
            LinkedList<Object> toVisit = new LinkedList<Object>();
            toVisit.add(rootNode);
            visitDepthFirst(toVisit, jw);
            jw.close();
            return sb.build();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private static void writeSpan(Span span, Map<String, String> stackTraces, JsonWriter jw,
            Appendable sb, Gson gson, boolean skipContextMap) throws IOException {

        jw.beginObject();
        jw.name("offset");
        jw.value(span.getOffset());
        jw.name("duration");
        jw.value(span.getDuration());
        jw.name("index");
        jw.value(span.getIndex());
        jw.name("parentIndex");
        jw.value(span.getParentIndex());
        jw.name("level");
        jw.value(span.getLevel());
        // inject raw json into stream
        sb.append(",\"description\":\"");
        sb.append(JsonCharSequence.escapeJson(span.getDescription()));
        sb.append("\"");
        // Span.getContextMap() may be creating the context map on the fly, so don't call it twice
        SpanContextMap contextMap = span.getContextMap();
        if (!skipContextMap && contextMap != null) {
            sb.append(",\"contextMap\":");
            sb.append(gson.toJson(contextMap, new TypeToken<Map<String, Object>>() {}.getType()));
        }
        if (span.getStackTraceElements() != null) {
            String stackTraceJson = getStackTraceJson(span.getStackTraceElements());
            String stackTraceHash = Hashing.sha1().hashString(stackTraceJson, Charsets.UTF_8)
                    .toString();
            stackTraces.put(stackTraceHash, stackTraceJson);
            jw.name("stackTraceHash");
            jw.value(stackTraceHash);
        }
        jw.endObject();
    }

    private static String getStackTraceJson(StackTraceElement[] stackTraceElements)
            throws IOException {

        LargeStringBuilder sb = new LargeStringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            jw.value(stackTraceElement.toString());
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }

    private static void visitDepthFirst(LinkedList<Object> toVisit, JsonWriter jw)
            throws IOException {

        while (!toVisit.isEmpty()) {
            Object curr = toVisit.removeLast();
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                if (currNode.isSyntheticRoot()) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(currNode.getStackTraceElement().toString());
                }
                jw.name("sampleCount").value(currNode.getSampleCount());
                if (currNode.isLeaf()) {
                    jw.name("leafThreadState").value(currNode.getLeafThreadState().name());
                }
                List<MergedStackTreeNode> childNodes = Lists.newArrayList(currNode.getChildNodes());
                if (childNodes.isEmpty()) {
                    jw.endObject();
                } else {
                    toVisit.add(JsonWriterOp.END_OBJECT);
                    jw.name("childNodes").beginArray();
                    toVisit.add(JsonWriterOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonWriterOp.END_ARRAY) {
                jw.endArray();
            } else if (curr == JsonWriterOp.END_OBJECT) {
                jw.endObject();
            }
        }
    }

    private static enum JsonWriterOp {
        END_OBJECT, END_ARRAY
    }
}
