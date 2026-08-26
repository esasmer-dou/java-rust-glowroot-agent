package com.reactor.glowroot.mock;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.HdrHistogram.Histogram;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.CollectorServiceGrpc;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateResponseMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueResponseMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.InitResponse;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OldAggregateMessage;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.OldTraceMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only collector that parses the real Glowroot protobuf contract. */
public final class MockCollectorApplication {

    private static final Set<String> REQUIRED_GAUGES = Set.of(
            "reactor.runtime:type=Process:ResidentSetSize",
            "reactor.runtime:type=Process:ThreadCount",
            "reactor.glowroot:type=Exporter:Connected",
            "reactor.glowroot:type=Exporter:FailureTotal",
            "reactor.glowroot:type=Exporter:DroppedTransactionTotal",
            "reactor.glowroot:type=Exporter:DroppedTraceTotal",
            "reactor.glowroot:type=Exporter:DroppedRouteTotal",
            "reactor.glowroot:type=Exporter:DroppedIntervalTotal",
            "reactor.glowroot:type=Exporter:ReconnectTotal",
            "reactor.glowroot:type=Exporter:LastErrorCode"
    );

    private MockCollectorApplication() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("MOCK_COLLECTOR_PORT", "8181"));
        Path report = Path.of(System.getenv().getOrDefault(
                "MOCK_COLLECTOR_REPORT", "/tmp/glowroot-mock-report.json"));
        Collector collector = new Collector(report);
        Server server = ServerBuilder.forPort(port).addService(collector).build().start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "mock-collector-shutdown"));
        collector.writeReport();
        System.out.println("Glowroot mock collector ready on port " + port);
        server.awaitTermination();
    }

    private static final class Collector extends CollectorServiceGrpc.CollectorServiceImplBase {
        private final Path report;
        private final AtomicLong initMessages = new AtomicLong();
        private final AtomicLong aggregateMessages = new AtomicLong();
        private final AtomicLong aggregateTransactions = new AtomicLong();
        private final AtomicLong webTransactions = new AtomicLong();
        private final AtomicLong webErrors = new AtomicLong();
        private final AtomicLong webDurationNanos = new AtomicLong();
        private final AtomicLong gaugeMessages = new AtomicLong();
        private final AtomicLong gaugeValues = new AtomicLong();
        private final AtomicLong traceMessages = new AtomicLong();
        private final AtomicLong validationErrors = new AtomicLong();
        private final AtomicReference<String> lastAgentId = new AtomicReference<>("");
        private final AtomicReference<String> lastError = new AtomicReference<>("");
        private final Set<String> transactionTypes = new ConcurrentSkipListSet<>();
        private final Set<String> transactionNames = new ConcurrentSkipListSet<>();
        private final ConcurrentHashMap<String, AtomicLong> transactionCounts =
                new ConcurrentHashMap<>();
        private final Set<String> gaugeNames = new ConcurrentSkipListSet<>();

        private Collector(Path report) {
            this.report = report;
        }

        @Override
        public void collectInit(InitMessage request, StreamObserver<InitResponse> response) {
            initMessages.incrementAndGet();
            lastAgentId.set(request.getAgentId());
            if (request.getAgentId().isBlank()) {
                fail("blank agent id in init");
            }
            if (!request.hasEnvironment() || !request.hasAgentConfig()) {
                fail("init is missing environment or agent config");
            }
            if (!request.getAgentConfig().getConfigReadOnly()) {
                fail("micro agent config must be read-only");
            }
            writeReport();
            response.onNext(InitResponse.newBuilder()
                    .setGlowrootCentralVersion("mock-current-contract")
                    .build());
            response.onCompleted();
        }

        @Override
        public void collectAggregates(
                OldAggregateMessage request,
                StreamObserver<AggregateResponseMessage> response) {
            aggregateMessages.incrementAndGet();
            lastAgentId.set(request.getAgentId());
            for (var byType : request.getAggregatesByTypeList()) {
                String transactionType = byType.getTransactionType();
                transactionTypes.add(transactionType);
                if (transactionType.isBlank()) fail("blank transaction type in aggregate");
                validateAggregate(
                        byType.getOverallAggregate(),
                        "overall:" + transactionType,
                        transactionType);
                for (var transaction : byType.getTransactionAggregateList()) {
                    String transactionName = transaction.getTransactionName();
                    Aggregate aggregate = transaction.getAggregate();
                    transactionNames.add(transactionName);
                    transactionCounts.computeIfAbsent(
                            transactionType + '|' + transactionName,
                            ignored -> new AtomicLong()).addAndGet(aggregate.getTransactionCount());
                    validateTransactionName(transactionType, transactionName);
                    validateAggregate(aggregate, transactionName, transactionType);
                    aggregateTransactions.addAndGet(aggregate.getTransactionCount());
                    if (transactionType.equals("Web")) {
                        webTransactions.addAndGet(aggregate.getTransactionCount());
                        webErrors.addAndGet(aggregate.getErrorCount());
                        webDurationNanos.addAndGet(Math.round(aggregate.getTotalDurationNanos()));
                    }
                }
            }
            writeReport();
            response.onNext(AggregateResponseMessage.newBuilder().setNextDelayMillis(0).build());
            response.onCompleted();
        }

        @Override
        public void collectGaugeValues(
                GaugeValueMessage request,
                StreamObserver<GaugeValueResponseMessage> response) {
            gaugeMessages.incrementAndGet();
            lastAgentId.set(request.getAgentId());
            gaugeValues.addAndGet(request.getGaugeValueCount());
            Set<String> received = new ConcurrentSkipListSet<>();
            request.getGaugeValueList().forEach(value -> received.add(value.getGaugeName()));
            gaugeNames.addAll(received);
            if (!received.containsAll(REQUIRED_GAUGES)) {
                fail("gauge message is missing required names: " + difference(REQUIRED_GAUGES, received));
            }
            writeReport();
            response.onNext(GaugeValueResponseMessage.newBuilder().setResendInit(false).build());
            response.onCompleted();
        }

        @Override
        public void collectTrace(OldTraceMessage request, StreamObserver<EmptyMessage> response) {
            traceMessages.incrementAndGet();
            lastAgentId.set(request.getAgentId());
            if (!request.hasTrace() || !request.getTrace().hasHeader()) {
                fail("trace is missing header");
            }
            writeReport();
            response.onNext(EmptyMessage.getDefaultInstance());
            response.onCompleted();
        }

        private void validateAggregate(Aggregate aggregate, String name, String transactionType) {
            if (aggregate.getErrorCount() < 0
                    || aggregate.getErrorCount() > aggregate.getTransactionCount()) {
                fail("invalid error count for " + name + ": " + aggregate.getErrorCount()
                        + " / " + aggregate.getTransactionCount());
            }
            if (aggregate.getTransactionCount() > 0 && aggregate.getTotalDurationNanos() < 0) {
                fail("negative duration for " + name);
            }
            String expectedRootTimer = switch (transactionType) {
                case "Web" -> "http request";
                case "SQL" -> "sql";
                case "Dubbo" -> "dubbo";
                case "Redis" -> "redis";
                default -> transactionType;
            };
            if (aggregate.getMainThreadRootTimerCount() != 1) {
                fail("aggregate must contain one root timer for " + name);
            } else {
                var rootTimer = aggregate.getMainThreadRootTimer(0);
                if (!rootTimer.getName().equals(expectedRootTimer)) {
                    fail("unexpected root timer for " + name + ": " + rootTimer.getName());
                }
                if (rootTimer.getCount() != aggregate.getTransactionCount()) {
                    fail("root timer count mismatch for " + name + ": " + rootTimer.getCount()
                            + " != " + aggregate.getTransactionCount());
                }
                if (Double.compare(rootTimer.getTotalNanos(), aggregate.getTotalDurationNanos()) != 0) {
                    fail("root timer duration mismatch for " + name);
                }
            }
            long histogramCount;
            Aggregate.Histogram histogram = aggregate.getDurationNanosHistogram();
            if (!histogram.getEncodedBytes().isEmpty()) {
                ByteBuffer bytes = histogram.getEncodedBytes().asReadOnlyByteBuffer();
                Histogram decoded = Histogram.decodeFromByteBuffer(bytes, 0);
                Histogram collectorTarget = new Histogram(1_000, 2_000, 5);
                collectorTarget.setAutoResize(true);
                collectorTarget.add(decoded);
                histogramCount = collectorTarget.getTotalCount();
            } else {
                histogramCount = histogram.getOrderedRawValueCount();
            }
            if (histogramCount != aggregate.getTransactionCount()) {
                fail("histogram count mismatch for " + name + ": " + histogramCount
                        + " != " + aggregate.getTransactionCount());
            }
        }

        private void validateTransactionName(String transactionType, String transactionName) {
            if (transactionName.isBlank()) {
                fail("blank transaction name for " + transactionType);
            }
            if (transactionType.equals("Web") && transactionName.matches(
                    "^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)\\s+.*")) {
                fail("Web transaction name must be route-only: " + transactionName);
            }
        }

        private void fail(String message) {
            validationErrors.incrementAndGet();
            lastError.set(message);
        }

        private synchronized void writeReport() {
            try {
                Path parent = report.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
                String json = "{" +
                        "\"healthy\":" + (validationErrors.get() == 0) + ',' +
                        "\"agent_id\":" + json(lastAgentId.get()) + ',' +
                        "\"init_messages\":" + initMessages.get() + ',' +
                        "\"aggregate_messages\":" + aggregateMessages.get() + ',' +
                        "\"aggregate_transactions\":" + aggregateTransactions.get() + ',' +
                        "\"web_transactions\":" + webTransactions.get() + ',' +
                        "\"web_errors\":" + webErrors.get() + ',' +
                        "\"web_duration_nanos\":" + webDurationNanos.get() + ',' +
                        "\"gauge_messages\":" + gaugeMessages.get() + ',' +
                        "\"gauge_values\":" + gaugeValues.get() + ',' +
                        "\"trace_messages\":" + traceMessages.get() + ',' +
                        "\"validation_errors\":" + validationErrors.get() + ',' +
                        "\"last_error\":" + json(lastError.get()) + ',' +
                        "\"transaction_types\":" + jsonArray(transactionTypes) + ',' +
                        "\"transaction_names\":" + jsonArray(transactionNames) + ',' +
                        "\"transaction_counts\":" + jsonLongMap(transactionCounts) + ',' +
                        "\"gauge_names\":" + jsonArray(gaugeNames) +
                        '}';
                Files.writeString(temporary, json, StandardCharsets.UTF_8);
                Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot write mock collector report", e);
            }
        }

        private static String jsonArray(Set<String> values) {
            return values.stream().map(Collector::json).reduce((left, right) -> left + ',' + right)
                    .map(value -> '[' + value + ']').orElse("[]");
        }

        private static String jsonLongMap(ConcurrentHashMap<String, AtomicLong> values) {
            return new TreeMap<>(values).entrySet().stream()
                    .map(entry -> json(entry.getKey()) + ':' + entry.getValue().get())
                    .reduce((left, right) -> left + ',' + right)
                    .map(value -> '{' + value + '}').orElse("{}");
        }

        private static Set<String> difference(Set<String> expected, Set<String> actual) {
            Set<String> missing = new ConcurrentSkipListSet<>(expected);
            missing.removeAll(actual);
            return missing;
        }

        private static String json(String value) {
            StringBuilder out = new StringBuilder(value.length() + 2).append('"');
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append(ch);
                }
            }
            return out.append('"').toString();
        }
    }
}
