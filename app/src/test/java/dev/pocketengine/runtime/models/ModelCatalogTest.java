package dev.pocketengine.runtime.models;

import dev.pocketengine.testutil.HttpServer.Exchange;
import dev.pocketengine.testutil.HttpServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.pocketengine.runtime.models.ModelFacts.*;
import static org.junit.Assert.*;

/** Uses real HTTP/socket/file behavior; no Android or provider success is implied. */
public class ModelCatalogTest {
    private final List<HttpServer> servers = new ArrayList<>();
    private Path directory;
    private MutableClock clock;
    @Before public void before() throws Exception { directory = Files.createTempDirectory("a21-catalog-test"); clock = new MutableClock(100_000); }
    @After public void after() throws Exception {
        for (HttpServer server : servers) server.stop(0);
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private ModelBinding binding(ModelBinding.Format format, URI endpoint) {
        ModelBinding.Protocol protocol = switch (format) {
            case OPENAI, OPENROUTER -> ModelBinding.Protocol.OPENAI_CHAT;
            case ANTHROPIC -> ModelBinding.Protocol.ANTHROPIC;
            case GEMINI -> ModelBinding.Protocol.GEMINI;
        };
        return new ModelBinding("native-connection", "provider-a", protocol, format, endpoint, "credential-a", 1, "installation-a", 1, false);
    }
    private ModelBinding binding(ModelBinding.Format format) { return binding(format, URI.create("https://vendor.example/v1/models")); }
    private ModelCatalog catalog() throws Exception { return new ModelCatalog(directory.resolve("catalog.json"), clock); }
    private CatalogHttpClient client() { return new CatalogHttpClient(clock, 5000); }
    private CatalogParser.Page parse(ModelBinding binding, String body) throws Exception { return CatalogParser.parse(binding, body, clock.millis(), 1); }
    private ModelFacts first(ModelBinding.Format format, String body) throws Exception { return parse(binding(format), body).models().values().iterator().next(); }
    private CatalogHttpClient.Snapshot snapshot(ModelBinding binding, String body) throws Exception { return new CatalogHttpClient.Snapshot(binding, clock.millis(), parse(binding, body).models()); }
    private void apply(ModelCatalog catalog, ModelBinding binding, String body) throws Exception {
        catalog.bind(binding); assertEquals(ModelCatalog.RefreshResult.APPLIED, catalog.commitRefresh(catalog.beginRefresh(binding), snapshot(binding, body)));
    }

    @Test public void standardOpenAiKeepsEveryCapabilityUnknownAndDoesNotReadNamesOrExtensions() throws Exception {
        ModelFacts facts = first(ModelBinding.Format.OPENAI, "{\"data\":[{\"id\":\"gpt-4o\",\"context_length\":999999,\"owned_by\":\"openai\",\"vision\":true,\"tools\":true}]}");
        for (Field field : Field.values()) {
            Fact<?> fact = facts.fact(field); assertFalse(field.name(), fact.known); assertNull(fact.value);
            assertEquals(Source.PROVIDER_CATALOG, fact.source); assertEquals(clock.millis(), fact.observedAtMillis);
            assertTrue(fact.sourceRef.contains("/data/0"));
        }
        assertFalse(facts.toJson().getJSONObject("OUTPUT_TOKENS").getBoolean("known"));
        assertTrue(facts.toJson().getJSONObject("OUTPUT_TOKENS").isNull("value"));
    }

    @Test public void geminiInputLimitIsNotACombinedContextAndGenerateContentDoesNotProveToolsOrImages() throws Exception {
        ModelFacts facts = first(ModelBinding.Format.GEMINI, "{\"models\":[{\"name\":\"models/test\",\"inputTokenLimit\":8192,\"outputTokenLimit\":1024,\"thinking\":false,\"supportedGenerationMethods\":[\"generateContent\"]}]}");
        assertEquals(new TokenWindow(8192, WindowKind.INPUT_ONLY), facts.contextWindow().value);
        assertEquals(Long.valueOf(1024), facts.outputTokens().value); assertEquals(Boolean.FALSE, facts.reasoning().value);
        assertFalse(facts.imageInput().known); assertFalse(facts.toolCalls().known); assertFalse(facts.reasoningLevels().known);
        assertEquals(ChatOutputParameter.NOT_APPLICABLE, facts.chatOutputParameter().value);
    }

    @Test public void claudeReadsExplicitCurrentCapabilitiesAndDoesNotConfuseServerTools() throws Exception {
        ModelFacts facts = first(ModelBinding.Format.ANTHROPIC, "{\"data\":[{\"id\":\"claude-test\",\"max_input_tokens\":200000,\"max_tokens\":64000,\"capabilities\":{\"image_input\":{\"supported\":false},\"server_tools\":{\"supported\":true},\"thinking\":{\"supported\":true},\"effort\":{\"supported\":true,\"low\":{\"supported\":true},\"medium\":{\"supported\":true},\"high\":{\"supported\":true},\"xhigh\":{\"supported\":false},\"max\":{\"supported\":false}}}}],\"has_more\":false}");
        assertEquals(WindowKind.INPUT_ONLY, facts.contextWindow().value.kind()); assertEquals(Long.valueOf(64000), facts.outputTokens().value);
        assertEquals(Boolean.FALSE, facts.imageInput().value); assertEquals(Boolean.TRUE, facts.reasoning().value);
        assertEquals(Set.of("low", "medium", "high"), Set.copyOf(facts.reasoningLevels().value)); assertFalse(facts.toolCalls().known);
        ModelFacts legacy = first(ModelBinding.Format.ANTHROPIC, "{\"data\":[{\"id\":\"legacy\"}],\"has_more\":false}");
        assertFalse(legacy.contextWindow().known); assertFalse(legacy.reasoning().known);
    }

    @Test public void openRouterUsesAdvertisedParametersRatherThanTheNameOfItsOutputLimit() throws Exception {
        ModelFacts facts = first(ModelBinding.Format.OPENROUTER, router("max_tokens\",\"tools\",\"reasoning"));
        assertEquals(new TokenWindow(32000, WindowKind.INPUT_AND_OUTPUT), facts.contextWindow().value);
        assertEquals(Long.valueOf(4096), facts.outputTokens().value); assertEquals(Boolean.TRUE, facts.imageInput().value);
        assertEquals(Boolean.TRUE, facts.toolCalls().value); assertEquals(Boolean.TRUE, facts.reasoning().value);
        assertEquals(ChatOutputParameter.MAX_TOKENS, facts.chatOutputParameter().value);
        ModelFacts modern = first(ModelBinding.Format.OPENROUTER, router("max_tokens\",\"max_completion_tokens"));
        assertEquals(ChatOutputParameter.MAX_COMPLETION_TOKENS, modern.chatOutputParameter().value);
        ModelFacts unknown = first(ModelBinding.Format.OPENROUTER, "{\"data\":[{\"id\":\"test\",\"top_provider\":{\"max_completion_tokens\":4096}}]}");
        assertTrue(unknown.outputTokens().known); assertFalse(unknown.chatOutputParameter().known);
    }

    @Test public void invalidOptionalLimitsDoNotTurnIntoZeroUnlimitedOrBooleanCapabilities() throws Exception {
        for (String input : List.of("0", "-1", "1.5", "\"8192\"", "null", "true")) {
            ModelFacts facts = first(ModelBinding.Format.GEMINI, "{\"models\":[{\"name\":\"models/test\",\"inputTokenLimit\":" + input + ",\"outputTokenLimit\":" + input + ",\"thinking\":\"true\"}]}");
            assertFalse(facts.contextWindow().known); assertFalse(facts.outputTokens().known); assertFalse(facts.reasoning().known);
            assertNull(facts.contextWindow().value);
        }
    }

    @Test public void openRouterDegradedStubsDoNotFabricateNegativeCapabilityAnswers() throws Exception {
        for (String body : List.of("{\"data\":[{\"id\":\"test\",\"context_length\":32000,\"supported_parameters\":[],\"architecture\":{\"input_modalities\":[\"text\"]}}]}",
                "{\"data\":[{\"id\":\"test\",\"supported_parameters\":[\"max_tokens\"],\"pricing\":{\"prompt\":\"-1\"}}]}")) {
            ModelFacts facts = first(ModelBinding.Format.OPENROUTER, body);
            for (Field field : Field.values()) { assertFalse(facts.fact(field).known); assertEquals("catalog_capabilities_unavailable", facts.fact(field).reason); }
        }
        ModelFacts routed = first(ModelBinding.Format.OPENROUTER, "{\"data\":[{\"id\":\"test\",\"context_length\":32000,\"top_provider\":{\"context_length\":16000},\"reasoning\":{\"supported_efforts\":[\"low\",\"high\"]},\"supported_parameters\":[\"max_tokens\",\"tool_choice\"]}]}");
        assertEquals(16000, routed.contextWindow().value.tokens()); assertTrue(routed.contextWindow().sourceRef.endsWith("/top_provider/context_length"));
        assertFalse(routed.toolCalls().known); assertEquals(List.of("low", "high"), routed.reasoningLevels().value);
    }

    @Test public void malformedDuplicateOrPartialPagesFailAsAWhole() throws Exception {
        ModelBinding binding = binding(ModelBinding.Format.OPENAI);
        for (String body : List.of("[]", "{\"error\":{\"message\":\"secret\"}}", "{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"}]}",
                "{\"data\":[{\"id\":\"a\"}],\"has_more\":true}", "{\"data\":[],\"has_more\":true,\"last_id\":\"a\"}",
                "{\"data\":[{\"id\":\" a\"}]}", "{\"data\":[{\"id\":123}]}", "{\"data\":[]} trailing")) assertThrows(CatalogException.class, () -> parse(binding, body));
        assertThrows(CatalogException.class, () -> parse(binding(ModelBinding.Format.ANTHROPIC), "{\"data\":[]}"));
    }

    @Test public void actualHttpFollowsAllPagesWithTheSameExactCredentialAndEndpoint() throws Exception {
        List<String> queries = new ArrayList<>(), authorization = new ArrayList<>();
        URI endpoint = serve(exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery()); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, queries.size() == 1 ? "{\"data\":[{\"id\":\"a\"}],\"has_more\":true,\"last_id\":\"a/+\"}" : "{\"data\":[{\"id\":\"b\"}],\"has_more\":false}");
        });
        ModelBinding binding = binding(ModelBinding.Format.OPENAI, endpoint); ModelCatalog catalog = catalog(); catalog.bind(binding);
        assertEquals(ModelCatalog.RefreshResult.APPLIED, catalog.refresh(binding, CatalogHttpClient.Headers.bearer("fixture-only-token"), client()));
        assertEquals(2, catalog.list(binding, 1000).size()); assertNull(queries.get(0)); assertEquals("limit=1000&after_id=a%2F%2B", queries.get(1));
        assertEquals(List.of("Bearer fixture-only-token", "Bearer fixture-only-token"), authorization);
        assertFalse(Files.readString(directory.resolve("catalog.json")).contains("fixture-only-token"));
    }

    @Test public void geminiUsesOpaquePageTokenAndNoKeyInTheUrl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        URI endpoint = serve(exchange -> {
            assertEquals("fixture-only", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            assertFalse(exchange.getRequestURI().toString().contains("fixture-only"));
            int n = calls.incrementAndGet(); if (n == 2) assertEquals("pageSize=1000&pageToken=opaque%2Btoken", exchange.getRequestURI().getRawQuery());
            reply(exchange, 200, n == 1 ? "{\"models\":[{\"name\":\"models/a\"}],\"nextPageToken\":\"opaque+token\"}" : "{\"models\":[{\"name\":\"models/b\"}]}");
        });
        CatalogHttpClient.Snapshot snapshot = client().fetch(binding(ModelBinding.Format.GEMINI, endpoint), CatalogHttpClient.Headers.gemini("fixture-only"), () -> true);
        assertEquals(2, snapshot.models().size()); assertEquals(2, calls.get());
    }

    @Test public void failedLaterPageDoesNotReplaceAnExistingCompleteSnapshot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        URI endpoint = serve(exchange -> { int n = calls.incrementAndGet(); reply(exchange, n == 2 ? 500 : 200,
                n == 1 ? "{\"data\":[{\"id\":\"new-page-only\"}],\"has_more\":true,\"last_id\":\"new-page-only\"}" : "untrusted private error"); });
        ModelBinding binding = binding(ModelBinding.Format.OPENAI, endpoint); ModelCatalog catalog = catalog();
        apply(catalog, binding, "{\"data\":[{\"id\":\"old-complete\"}]}");
        byte[] before = Files.readAllBytes(directory.resolve("catalog.json"));
        CatalogException error = assertThrows(CatalogException.class, () -> catalog.refresh(binding, CatalogHttpClient.Headers.none(), client()));
        assertEquals(CatalogException.Code.HTTP_ERROR, error.code); assertArrayEquals(before, Files.readAllBytes(directory.resolve("catalog.json")));
        assertEquals("old-complete", catalog.list(binding, 1000).get(0).key().modelId());
    }

    @Test public void repeatedCursorOrDuplicateLaterModelCannotPublishAHalfCatalog() throws Exception {
        URI endpoint = serve(exchange -> reply(exchange, 200, "{\"data\":[{\"id\":\"a\"}],\"has_more\":true,\"last_id\":\"a\"}"));
        CatalogException error = assertThrows(CatalogException.class, () -> client().fetch(binding(ModelBinding.Format.OPENAI, endpoint), CatalogHttpClient.Headers.none(), () -> true));
        assertEquals(CatalogException.Code.PAGINATION_INCOMPLETE, error.code);
    }

    @Test public void redirectAndForeignPaginationNeverForwardCredentials() throws Exception {
        AtomicInteger foreignCalls = new AtomicInteger(); URI foreign = serve(exchange -> { foreignCalls.incrementAndGet(); reply(exchange, 200, "{\"data\":[]}"); });
        URI redirect = serve(exchange -> { exchange.getResponseHeaders().set("Location", foreign.toString()); reply(exchange, 302, "private redirect body"); });
        CatalogException error = assertThrows(CatalogException.class, () -> client().fetch(binding(ModelBinding.Format.OPENAI, redirect), CatalogHttpClient.Headers.bearer("fixture-only-token"), () -> true));
        assertEquals(CatalogException.Code.REDIRECT_REFUSED, error.code);
        URI linked = serve(exchange -> reply(exchange, 200, "{\"data\":[{\"id\":\"a\"}],\"links\":{\"next\":\"" + foreign + "\"}}"));
        assertEquals(CatalogException.Code.PAGINATION_INCOMPLETE, assertThrows(CatalogException.class,
                () -> client().fetch(binding(ModelBinding.Format.OPENROUTER, linked), CatalogHttpClient.Headers.bearer("fixture-only-token"), () -> true)).code);
        assertEquals(0, foreignCalls.get());
    }

    @Test public void httpErrorsExposeOnlyClassAndStatusAndDoNotTryAnotherAuthentication() throws Exception {
        for (int status : List.of(401, 403, 429, 500)) {
            AtomicInteger calls = new AtomicInteger(); URI endpoint = serve(exchange -> { calls.incrementAndGet(); reply(exchange, status, "fixture-only-token and private account error"); });
            CatalogException error = assertThrows(CatalogException.class, () -> client().fetch(binding(ModelBinding.Format.OPENAI, endpoint), CatalogHttpClient.Headers.bearer("fixture-only-token"), () -> true));
            assertEquals(status, error.httpStatus); assertFalse(error.toString().contains("fixture-only")); assertNull(error.getCause()); assertEquals(1, calls.get());
        }
    }

    @Test public void totalDeadlineInterruptsSlowResponseHeadersWhileIndividualReadsKeepProgressing() throws Exception {
        CatalogHttpClient client = new CatalogHttpClient(clock, 300);
        AtomicInteger sent = new AtomicInteger();
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread responder = new Thread(() -> {
                try (java.net.Socket connection = socket.accept()) {
                    connection.setTcpNoDelay(true); connection.setSoTimeout(2000);
                    int matched = 0; int[] ending = {13, 10, 13, 10};
                    while (matched < 4) { int next = connection.getInputStream().read(); if (next < 0) return; matched = next == ending[matched] ? matched + 1 : 0; }
                    java.io.OutputStream output = connection.getOutputStream();
                    output.write("HTTP/1.1 200 OK\r\nX-Slow: ".getBytes(StandardCharsets.US_ASCII)); output.flush();
                    for (int n = 0; n < 16; n++) { output.write('x'); output.flush(); sent.incrementAndGet(); Thread.sleep(70); }
                    byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                    output.write(("\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
                } catch (Exception closed) { /* The deadline is expected to close this fixture socket. */ }
            }, "a21-header-deadline-regression");
            responder.setDaemon(true); responder.start();
            ModelBinding binding = binding(ModelBinding.Format.OPENAI, URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/v1/models"));
            long started = System.nanoTime();
            CatalogException error = assertThrows(CatalogException.class, () -> client.fetch(binding, CatalogHttpClient.Headers.none(), () -> true));
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            assertEquals(CatalogException.Code.NETWORK_ERROR, error.code);
            assertTrue("A total 300ms deadline must not wait for all 16 header chunks: " + elapsed, elapsed < 850);
            responder.join(2000); assertTrue("The slow response header must actually start", sent.get() > 0);
            assertTrue("The call must terminate before the slow header finishes", sent.get() < 16);
        }
    }

    @Test public void pagesShareTheOriginalDeadlineRatherThanRestartingTheCallBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        URI endpoint = serve(exchange -> {
            int page = calls.incrementAndGet();
            try { Thread.sleep(page == 1 ? 180 : 350); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, page == 1 ? "{\"data\":[{\"id\":\"first\"}],\"has_more\":true,\"last_id\":\"first\"}" : "{\"data\":[{\"id\":\"second\"}]}");
        });
        ModelBinding binding = binding(ModelBinding.Format.OPENAI, endpoint); ModelCatalog catalog = catalog();
        apply(catalog, binding, "{\"data\":[{\"id\":\"old-complete\"}]}");
        CatalogHttpClient client = new CatalogHttpClient(clock, 300); long started = System.nanoTime();
        CatalogException error = assertThrows(CatalogException.class, () -> catalog.refresh(binding, CatalogHttpClient.Headers.none(), client));
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(CatalogException.Code.NETWORK_ERROR, error.code); assertEquals(2, calls.get());
        assertTrue("The second page must receive only the remaining deadline: " + elapsed, elapsed < 430);
        assertEquals("old-complete", catalog.list(binding, 1000).get(0).key().modelId());
    }

    @Test public void oversizedAndNonUtf8ResponsesFailBeforePublishing() throws Exception {
        URI endpoint = serve(exchange -> { exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 3L * 1024 * 1024); exchange.close(); });
        assertEquals(CatalogException.Code.TOO_LARGE, assertThrows(CatalogException.class, () -> client().fetch(binding(ModelBinding.Format.OPENAI, endpoint), CatalogHttpClient.Headers.none(), () -> true)).code);
        URI invalid = serve(exchange -> { byte[] body = {(byte) 0xc3, (byte) 0x28}; exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        assertEquals(CatalogException.Code.INVALID_CATALOG, assertThrows(CatalogException.class, () -> client().fetch(binding(ModelBinding.Format.OPENAI, invalid), CatalogHttpClient.Headers.none(), () -> true)).code);
    }

    @Test public void ownerValuesSurviveAnAlreadyStartedRefreshAndRestartWithTheirOwnRevision() throws Exception {
        ModelCatalog catalog = catalog(); ModelBinding binding = binding(ModelBinding.Format.OPENROUTER); catalog.bind(binding);
        ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(binding, "test"); ModelCatalog.Ticket ticket = catalog.beginRefresh(binding);
        assertEquals(1, catalog.updateOwner(key, 0, Map.of(Field.IMAGE_INPUT, OwnerValue.known(false), Field.CONTEXT_WINDOW, OwnerValue.known(new TokenWindow(12345, WindowKind.INPUT_AND_OUTPUT))), Set.of()));
        assertEquals(ModelCatalog.RefreshResult.APPLIED, catalog.commitRefresh(ticket, snapshot(binding, router("max_tokens\",\"tools"))));
        ModelCatalog.View view = catalog.get(key, 1000); assertEquals(Boolean.FALSE, view.facts().imageInput().value); assertEquals(12345, view.facts().contextWindow().value.tokens());
        assertEquals(Source.OWNER_OVERRIDE, view.facts().imageInput().source); assertEquals(Source.PROVIDER_CATALOG, view.facts().outputTokens().source);
        ModelCatalog restored = catalog(); assertThrows(IllegalStateException.class, () -> restored.get(key, 1000)); restored.bind(binding);
        assertEquals(1, restored.get(key, 1000).ownerRevision()); assertEquals(Boolean.FALSE, restored.get(key, 1000).facts().imageInput().value);
        assertThrows(IllegalStateException.class, () -> restored.updateOwner(key, 0, Map.of(Field.IMAGE_INPUT, OwnerValue.known(true)), Set.of()));
    }

    @Test public void ownerUnknownAndClearAreDifferentAndClearedRevisionCannotBeReused() throws Exception {
        ModelCatalog catalog = catalog(); ModelBinding binding = binding(ModelBinding.Format.OPENROUTER); apply(catalog, binding, router("max_tokens"));
        ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(binding, "test");
        assertEquals(1, catalog.updateOwner(key, 0, Map.of(Field.IMAGE_INPUT, OwnerValue.unknown()), Set.of()));
        assertFalse(catalog.get(key, 1000).facts().imageInput().known); assertEquals(Source.OWNER_OVERRIDE, catalog.get(key, 1000).facts().imageInput().source);
        assertEquals(2, catalog.updateOwner(key, 1, Map.of(), Set.of(Field.IMAGE_INPUT))); assertEquals(Boolean.TRUE, catalog.get(key, 1000).facts().imageInput().value);
        ModelCatalog restored = catalog(); restored.bind(binding); assertEquals(2, restored.get(key, 1000).ownerRevision());
        assertThrows(IllegalStateException.class, () -> restored.updateOwner(key, 0, Map.of(Field.IMAGE_INPUT, OwnerValue.known(false)), Set.of()));
    }

    @Test public void newerRefreshAndEveryBindingDimensionFenceOldResultsAndOverrides() throws Exception {
        ModelCatalog catalog = catalog(); ModelBinding a = binding(ModelBinding.Format.OPENAI); catalog.bind(a);
        ModelCatalog.Ticket old = catalog.beginRefresh(a), latest = catalog.beginRefresh(a);
        assertEquals(ModelCatalog.RefreshResult.SUPERSEDED, catalog.commitRefresh(old, snapshot(a, "{\"data\":[{\"id\":\"old\"}]}")));
        assertEquals(ModelCatalog.RefreshResult.APPLIED, catalog.commitRefresh(latest, snapshot(a, "{\"data\":[{\"id\":\"new\"}]}")));
        ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(a, "new"); catalog.updateOwner(key, 0, Map.of(Field.OUTPUT_TOKENS, OwnerValue.known(1000)), Set.of());
        for (ModelBinding changed : List.of(
                new ModelBinding(a.connectionId(), a.providerId(), a.protocol(), a.format(), a.modelsEndpoint(), "credential-b", 1, a.installationId(), 1, false),
                new ModelBinding(a.connectionId(), a.providerId(), a.protocol(), a.format(), a.modelsEndpoint(), a.credentialId(), 2, a.installationId(), 1, false),
                new ModelBinding(a.connectionId(), a.providerId(), a.protocol(), a.format(), a.modelsEndpoint(), a.credentialId(), 1, a.installationId(), 2, false),
                new ModelBinding(a.connectionId(), a.providerId(), a.protocol(), a.format(), URI.create("https://other.example/v1/models"), a.credentialId(), 1, a.installationId(), 1, false))) {
            ModelCatalog.Ticket inflight = catalog.beginRefresh(a); catalog.bind(changed);
            assertThrows(IllegalStateException.class, () -> catalog.get(key, 1000));
            assertFalse(catalog.get(new ModelCatalog.ModelKey(changed, "new"), 1000).facts().outputTokens().known);
            catalog.bind(a); assertEquals(ModelCatalog.RefreshResult.SUPERSEDED, catalog.commitRefresh(inflight, snapshot(a, "{\"data\":[]}")));
        }
        assertEquals("new", catalog.list(a, 1000).get(0).key().modelId());
    }

    @Test public void expiryAndCompleteEmptyListRetainOwnerChoiceWithoutClaimingAvailability() throws Exception {
        ModelCatalog catalog = catalog(); ModelBinding binding = binding(ModelBinding.Format.OPENROUTER); apply(catalog, binding, router("max_tokens"));
        ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(binding, "test"); catalog.updateOwner(key, 0, Map.of(Field.IMAGE_INPUT, OwnerValue.known(false)), Set.of());
        clock.millis += 2000; ModelCatalog.View stale = catalog.get(key, 1000);
        assertFalse(stale.catalogFresh()); assertFalse(stale.facts().outputTokens().known); assertFalse(stale.listed().known); assertEquals(Boolean.FALSE, stale.facts().imageInput().value);
        apply(catalog, binding, "{\"data\":[]}"); ModelCatalog.View gone = catalog.get(key, 1000);
        assertEquals(Boolean.FALSE, gone.listed().value); assertEquals(Boolean.FALSE, gone.facts().imageInput().value);
        assertFalse(gone.facts().contextWindow().known); assertEquals(1, gone.ownerRevision()); assertEquals("test", catalog.list(binding, 1000).get(0).key().modelId());
    }

    @Test public void inFlightHttpRefreshCannotOverwriteOwnerChanges() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        URI endpoint = serve(exchange -> { entered.countDown(); try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } reply(exchange, 200, router("max_tokens")); });
        ModelBinding binding = binding(ModelBinding.Format.OPENROUTER, endpoint); ModelCatalog catalog = catalog(); catalog.bind(binding);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> catalog.refresh(binding, CatalogHttpClient.Headers.none(), client()));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(binding, "test");
            catalog.updateOwner(key, 0, Map.of(Field.IMAGE_INPUT, OwnerValue.known(false)), Set.of()); release.countDown();
            assertEquals(ModelCatalog.RefreshResult.APPLIED, future.get(3, TimeUnit.SECONDS)); assertEquals(Boolean.FALSE, catalog.get(key, 1000).facts().imageInput().value);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void invalidCacheIsVisibleAndFailedSaveDoesNotChangeTheLiveOwnerRevision() throws Exception {
        Files.writeString(directory.resolve("catalog.json"), "not json");
        assertEquals(CatalogException.Code.CACHE_INVALID, assertThrows(CatalogException.class, this::catalog).code);
        Files.delete(directory.resolve("catalog.json")); ModelCatalog catalog = catalog(); ModelBinding binding = binding(ModelBinding.Format.OPENAI); catalog.bind(binding);
        Files.createDirectory(directory.resolve("catalog.json")); ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(binding, "test");
        assertThrows(java.io.IOException.class, () -> catalog.updateOwner(key, 0, Map.of(Field.OUTPUT_TOKENS, OwnerValue.known(1000)), Set.of()));
        assertEquals(0, catalog.get(key, 1000).ownerRevision()); assertFalse(catalog.get(key, 1000).facts().outputTokens().known);
    }

    @Test public void changingBindingDuringActualHttpCancelsTheOldResultWithoutPublishingIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        URI endpoint = serve(exchange -> { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } reply(exchange, 200, "{\"data\":[{\"id\":\"old\"}]}"); });
        ModelBinding binding = binding(ModelBinding.Format.OPENAI, endpoint); ModelCatalog catalog = catalog(); catalog.bind(binding);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> catalog.refresh(binding, CatalogHttpClient.Headers.none(), client()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ModelBinding changed = new ModelBinding(binding.connectionId(), binding.providerId(), binding.protocol(), binding.format(), endpoint,
                    binding.credentialId(), 2, binding.installationId(), binding.installationRevision(), false);
            catalog.bind(changed); release.countDown();
            java.util.concurrent.ExecutionException error = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertEquals(CatalogException.Code.CANCELLED, ((CatalogException) error.getCause()).code);
            catalog.bind(binding); assertTrue(catalog.list(binding, 1000).isEmpty()); assertFalse(Files.exists(directory.resolve("catalog.json")));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void sameEndpointLinksWorkAndUnboundedPaginationFailsAfterTheBound() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        URI linked = serve(exchange -> { int n = calls.incrementAndGet(); reply(exchange, 200, n == 1
                ? "{\"data\":[{\"id\":\"a\"}],\"links\":{\"next\":\"?page=2\"}}" : "{\"data\":[{\"id\":\"b\"}],\"links\":{\"next\":null}}"); });
        assertEquals(2, client().fetch(binding(ModelBinding.Format.OPENROUTER, linked), CatalogHttpClient.Headers.none(), () -> true).models().size());
        AtomicInteger pages = new AtomicInteger(); URI unbounded = serve(exchange -> { int n = pages.incrementAndGet(); reply(exchange, 200,
                "{\"data\":[{\"id\":\"model-" + n + "\"}],\"has_more\":true,\"last_id\":\"model-" + n + "\"}"); });
        assertEquals(CatalogException.Code.PAGINATION_INCOMPLETE, assertThrows(CatalogException.class,
                () -> client().fetch(binding(ModelBinding.Format.OPENAI, unbounded), CatalogHttpClient.Headers.none(), () -> true)).code);
        assertEquals(16, pages.get());
    }

    @Test public void malformedCacheAndProvenanceCannotMasqueradeAsAnOwnerEditOrFreshProviderValue() throws Exception {
        ModelCatalog catalog = catalog(); ModelBinding binding = binding(ModelBinding.Format.OPENAI); catalog.bind(binding);
        ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(binding, "test");
        catalog.updateOwner(key, 0, Map.of(Field.OUTPUT_TOKENS, OwnerValue.known(1000)), Set.of());
        ModelFacts spoofed = ModelFacts.unknown(binding).withOverrides(Map.of(Field.OUTPUT_TOKENS,
                ModelFacts.ownerFact(Field.OUTPUT_TOKENS, OwnerValue.known(9999), 1, clock.millis())));
        ModelCatalog.Ticket ticket = catalog.beginRefresh(binding);
        assertThrows(IllegalArgumentException.class, () -> catalog.commitRefresh(ticket,
                new CatalogHttpClient.Snapshot(binding, clock.millis(), Map.of("test", spoofed))));
        assertEquals(Long.valueOf(1000), catalog.get(key, 1000).facts().outputTokens().value);
        JSONObject cache = new JSONObject(Files.readString(directory.resolve("catalog.json")));
        cache.getJSONArray("overrides").getJSONObject(0).getJSONObject("fields").getJSONObject("OUTPUT_TOKENS").put("source", "PROVIDER_CATALOG");
        Files.writeString(directory.resolve("catalog.json"), cache.toString());
        assertEquals(CatalogException.Code.CACHE_INVALID, assertThrows(CatalogException.class, this::catalog).code);
    }

    @Test public void exactTransportConsentAndTypedOwnerParametersAreRequired() throws Exception {
        ModelBinding a = binding(ModelBinding.Format.OPENAI); ModelCatalog catalog = catalog(); catalog.bind(a); ModelCatalog.ModelKey key = new ModelCatalog.ModelKey(a, "test");
        assertThrows(IllegalArgumentException.class, () -> binding(ModelBinding.Format.OPENAI, URI.create("http://example.com/v1/models")));
        assertThrows(IllegalArgumentException.class, () -> binding(ModelBinding.Format.OPENAI, URI.create("https://user:secret@example.com/v1/models")));
        assertThrows(IllegalArgumentException.class, () -> binding(ModelBinding.Format.OPENAI, URI.create("https://example.com/v1/models?key=secret")));
        assertThrows(IllegalArgumentException.class, () -> catalog.updateOwner(key, 0, Map.of(Field.OUTPUT_TOKENS, OwnerValue.known("1000")), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.updateOwner(key, 0, Map.of(Field.CHAT_OUTPUT_PARAMETER, OwnerValue.known(ChatOutputParameter.NOT_APPLICABLE)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> CatalogHttpClient.Headers.bearer("token\r\nInjected: secret"));
        assertThrows(IllegalArgumentException.class, () -> CatalogHttpClient.Headers.bearer(null));
        assertThrows(IllegalArgumentException.class, () -> CatalogHttpClient.Headers.bearer(""));
    }

    private String router(String parameters) { return "{\"data\":[{\"id\":\"test\",\"context_length\":32000,\"top_provider\":{\"max_completion_tokens\":4096},\"architecture\":{\"input_modalities\":[\"text\",\"image\"]},\"supported_parameters\":[\"" + parameters + "\"]}]}"; }
    private URI serve(dev.pocketengine.testutil.HttpServer.Handler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.createContext("/v1/models", handler); server.start(); servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/models");
    }
    private static void reply(Exchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static final class MutableClock extends Clock {
        long millis; MutableClock(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
