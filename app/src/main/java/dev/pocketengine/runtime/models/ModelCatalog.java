package dev.pocketengine.runtime.models;

import dev.pocketengine.runtime.usage.ModelPrice;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.pocketengine.runtime.models.ModelFacts.*;

/** Native metadata cache only. Does not select a model, own tasks, refresh accounts or run inference. */
public final class ModelCatalog {
    private static final int MAX_CACHE_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private final Clock clock;
    private final ModelKnowledgeRegistry publicIndex;
    private final Map<String, ModelBinding> active = new LinkedHashMap<>();
    private final Map<String, Long> generations = new LinkedHashMap<>();
    private Map<ModelBinding, CatalogHttpClient.Snapshot> snapshots = new LinkedHashMap<>();
    private Map<ModelKey, OwnerRecord> overrides = new LinkedHashMap<>();

    public record ModelKey(ModelBinding binding, String modelId) {
        public ModelKey { Objects.requireNonNull(binding); modelId = ModelBinding.modelId(modelId); }
    }
    private record OwnerRecord(long revision, Map<Field, Fact<?>> fields,ModelPrice price) { }
    public record View(ModelKey key, long ownerRevision, boolean catalogFresh, long catalogFetchedAtMillis,
                       Fact<Boolean> listed, ModelFacts facts, ModelEndpointSupport endpointSupport) {
        public JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("binding", key.binding().json()).put("modelId", key.modelId())
                    .put("ownerRevision", ownerRevision).put("catalogFresh", catalogFresh)
                    .put("catalogFetchedAtMillis", catalogFetchedAtMillis == 0 ? JSONObject.NULL : catalogFetchedAtMillis)
                    .put("listed", listed.toJson()).put("facts", facts.toJson()).put("endpointSupport",endpointSupport==null?JSONObject.NULL:endpointSupport.toJson())
                    .put("protocolCompatibility",endpointSupport==null?"unknown":endpointSupport.supports(key.binding().protocol())?"declared_compatible":"declared_incompatible");
        }
    }
    public enum RefreshResult { APPLIED, SUPERSEDED }

    /** Opaque admission for one refresh. A newer refresh or binding invalidates this one. */
    public static final class Ticket {
        private final ModelCatalog owner;
        private final ModelBinding binding;
        private final long generation, startedAtMillis;
        private Ticket(ModelCatalog owner, ModelBinding binding, long generation, long started) {
            this.owner = owner; this.binding = binding; this.generation = generation; this.startedAtMillis = started;
        }
    }

    /** file must be an app-private, single-writer cache path, separate from SettingsStore/TaskStore. */
    public ModelCatalog(Path file, Clock clock) throws IOException {
        this.file = Objects.requireNonNull(file).toAbsolutePath();
        publicIndex = new ModelKnowledgeRegistry(this.file.resolveSibling(this.file.getFileName().toString() + ".model-index.gz"), clock);
        this.clock = Objects.requireNonNull(clock);
        load();
    }

    Path selectionMetadataPath() { return file.resolveSibling(file.getFileName().toString() + ".model-preferences"); }
    public void refreshPublicIndexAsync() { publicIndex.refreshIfDueAsync(); }
    public JSONObject publicIndexStatus() throws org.json.JSONException { return publicIndex.status(); }

    Path thirdPartyMetadataPath() { return file.resolveSibling(file.getFileName().toString() + ".metadata-suggestions"); }

    /** Call from the native host's current config/account/install identity, including every revision. */
    public synchronized void bind(ModelBinding binding) {
        Objects.requireNonNull(binding);
        if (!binding.equals(active.get(binding.connectionId()))) {
            active.put(binding.connectionId(), binding); advance(binding.connectionId());
        }
    }

    /** In-flight bytes may finish reading, but cannot publish or request another page. */
    public synchronized void unbind(String connectionId) { active.remove(connectionId); advance(connectionId); }
    public synchronized void cancelRefresh(ModelBinding binding) { requireActive(binding); advance(binding.connectionId()); }

    public synchronized Ticket beginRefresh(ModelBinding binding) {
        requireActive(binding);
        return new Ticket(this, binding, advance(binding.connectionId()), clock.millis());
    }

    public synchronized boolean isCurrent(Ticket ticket) {
        return ticket != null && ticket.owner == this && ticket.binding.equals(active.get(ticket.binding.connectionId()))
                && generations.getOrDefault(ticket.binding.connectionId(), 0L) == ticket.generation;
    }

    public RefreshResult refresh(ModelBinding binding, CatalogHttpClient.Headers headers, CatalogHttpClient client) throws IOException {
        Ticket ticket = beginRefresh(binding);
        CatalogHttpClient.Snapshot fetched = client.fetch(binding, headers, () -> isCurrent(ticket));
        return commitRefresh(ticket, fetched);
    }

    /** Only a complete successful fetch may reach this method. Overrides are never read from it. */
    public synchronized RefreshResult commitRefresh(Ticket ticket, CatalogHttpClient.Snapshot fetched) throws IOException {
        if (!isCurrent(ticket)) return RefreshResult.SUPERSEDED;
        if (!ticket.binding.equals(fetched.binding()) || fetched.fetchedAtMillis() < ticket.startedAtMillis
                || fetched.fetchedAtMillis() > clock.millis() || fetched.models().size() > 10000) throw new IllegalArgumentException("refresh_identity_or_time_mismatch");
        for (Map.Entry<String, ModelFacts> row : fetched.models().entrySet()) {
            ModelBinding.modelId(row.getKey());
            for (Field field : Field.values()) {
                Fact<?> fact = row.getValue().fact(field);
                if (fact.source != Source.PROVIDER_CATALOG || fact.observedAtMillis < ticket.startedAtMillis
                        || fact.observedAtMillis > fetched.fetchedAtMillis()) throw new IllegalArgumentException("refresh_fact_provenance_mismatch");
            }
        }
        for(var entry:fetched.prices().entrySet()){
            ModelPrice price=entry.getValue();if(!fetched.models().containsKey(entry.getKey())||!price.binding.equals(ticket.binding)||!price.model.equals(entry.getKey())
                ||price.source!=ModelPrice.Source.PROVIDER_CATALOG||price.observedAt<ticket.startedAtMillis||price.observedAt>fetched.fetchedAtMillis())throw new IllegalArgumentException("refresh_price_provenance_mismatch");
        }
        for(var entry:fetched.endpointSupport().entrySet())validateEndpointSupport(ticket.binding,fetched.models(),entry.getKey(),entry.getValue(),ticket.startedAtMillis,fetched.fetchedAtMillis());
        Map<ModelBinding, CatalogHttpClient.Snapshot> next = new LinkedHashMap<>(snapshots); next.put(ticket.binding, fetched);
        save(next, overrides); snapshots = next;
        // A ticket is single-use, including a duplicate completion after owner edits.
        advance(ticket.binding.connectionId());
        return RefreshResult.APPLIED;
    }

    /** Atomic per-model CAS. An empty override record keeps its revision as a stale-write tombstone. */
    public synchronized long updateOwner(ModelKey key, long expectedRevision, Map<Field, OwnerValue> set, Set<Field> clear) throws IOException {
        requireActive(key.binding()); Objects.requireNonNull(set); Objects.requireNonNull(clear);
        OwnerRecord previous = overrides.get(key);
        long actual = previous == null ? 0 : previous.revision();
        if (expectedRevision != actual) throw new IllegalStateException("owner_revision_conflict");
        if (set.isEmpty() && clear.isEmpty()) return actual;
        if (!Collections.disjoint(set.keySet(), clear)) throw new IllegalArgumentException("override_set_clear_overlap");
        long revision = Math.addExact(actual, 1);
        Map<Field, Fact<?>> fields = new EnumMap<>(Field.class);
        if (previous != null) fields.putAll(previous.fields());
        for (Field field : clear) fields.remove(Objects.requireNonNull(field));
        for (Map.Entry<Field, OwnerValue> row : set.entrySet()) {
            Field field = Objects.requireNonNull(row.getKey());
            Fact<?> fact = ownerFact(field, Objects.requireNonNull(row.getValue()), revision, clock.millis());
            if (field == Field.CHAT_OUTPUT_PARAMETER && fact.known
                    && (key.binding().protocol() == ModelBinding.Protocol.OPENAI_CHAT)
                    == (fact.value == ChatOutputParameter.NOT_APPLICABLE)) throw new IllegalArgumentException("chat_parameter_protocol_mismatch");
            fields.put(field, fact);
        }
        Map<ModelKey, OwnerRecord> next = new LinkedHashMap<>(overrides);
        next.put(key, new OwnerRecord(revision, Collections.unmodifiableMap(fields),previous==null?null:previous.price()));
        save(snapshots, next); overrides = next;
        return revision;
    }

    /** Optional money quote, independent from capability enforcement and never an inference grant. */
    public synchronized ModelPrice price(ModelKey key,long maxCatalogAgeMillis){
        requireActive(key.binding());if(maxCatalogAgeMillis<1)throw new IllegalArgumentException("positive_catalog_age_required");
        long now=clock.millis();OwnerRecord owner=overrides.get(key);
        if(owner!=null&&owner.price()!=null)return owner.price().validAt(now)?owner.price():null;
        CatalogHttpClient.Snapshot observed=snapshots.get(key.binding());
        if(observed==null||now<observed.fetchedAtMillis()||now-observed.fetchedAtMillis()>maxCatalogAgeMillis)return null;
        ModelPrice price=observed.prices().get(key.modelId());return price!=null&&price.validAt(now)?price:null;
    }
    /** Last real quote for owner explanation only. Call price(...) for current capture eligibility. */
    public synchronized ModelPrice priceObservation(ModelKey key){requireActive(key.binding());OwnerRecord owner=overrides.get(key);if(owner!=null&&owner.price()!=null)return owner.price();CatalogHttpClient.Snapshot snapshot=snapshots.get(key.binding());return snapshot==null?null:snapshot.prices().get(key.modelId());}
    public synchronized long updateOwnerPrice(ModelKey key,long expectedRevision,ModelPrice price)throws Exception{
        requireActive(key.binding());OwnerRecord old=overrides.get(key);long actual=old==null?0:old.revision();
        if(actual!=expectedRevision)throw new IllegalStateException("owner_revision_conflict");
        if(price!=null&&(!price.binding.equals(key.binding())||!price.model.equals(key.modelId())||price.source!=ModelPrice.Source.OWNER_REVIEWED))throw new SecurityException("owner_price_identity_mismatch");
        long revision=Math.addExact(actual,1);Map<ModelKey,OwnerRecord> next=new LinkedHashMap<>(overrides);
        next.put(key,new OwnerRecord(revision,old==null?Map.of():old.fields(),price));save(snapshots,next);overrides=next;return revision;
    }

    /** Expired catalog values become unknown; current owner fields keep their own time and source. */
    public synchronized View get(ModelKey key, long maxCatalogAgeMillis) {
        requireActive(key.binding());
        if (maxCatalogAgeMillis < 1) throw new IllegalArgumentException("positive_catalog_age_required");
        CatalogHttpClient.Snapshot snapshot = snapshots.get(key.binding());
        long now = clock.millis(), fetchedAt = snapshot == null ? 0 : snapshot.fetchedAtMillis();
        boolean fresh = snapshot != null && now >= fetchedAt && now - fetchedAt <= maxCatalogAgeMillis;
        ModelFacts facts = snapshot == null ? null : snapshot.models().get(key.modelId());
        if (facts == null) facts = ModelFacts.unknown(key.binding());
        if (snapshot != null && !fresh) facts = facts.expired();
        // Provider facts win when explicit; local family defaults fill only unknowns.
        // Owner declarations are applied afterward and always take precedence.
        facts = publicIndex.enrich(key.binding(), key.modelId(), facts);
        facts = ModelReasoningProfiles.withFamilyDefaults(key.binding(), key.modelId(), facts);
        OwnerRecord owner = overrides.get(key);
        if (owner != null) facts = facts.withOverrides(owner.fields());
        Fact<Boolean> listed = snapshot == null ? Fact.unknown(Source.UNOBSERVED, key.binding().modelsEndpoint().toASCIIString(), 0, "not_observed")
                : fresh ? Fact.known(snapshot.models().containsKey(key.modelId()), Source.PROVIDER_CATALOG, key.binding().modelsEndpoint().toASCIIString(), fetchedAt)
                : Fact.unknown(Source.PROVIDER_CATALOG, key.binding().modelsEndpoint().toASCIIString(), fetchedAt, "catalog_expired");
        return new View(key, owner == null ? 0 : owner.revision(), fresh, fetchedAt, listed, facts,fresh?snapshot.endpointSupport().get(key.modelId()):null);
    }

    public synchronized List<View> list(ModelBinding binding, long maxCatalogAgeMillis) {
        requireActive(binding);
        if (maxCatalogAgeMillis < 1) throw new IllegalArgumentException("positive_catalog_age_required");
        Map<String, Boolean> ids = new LinkedHashMap<>();
        CatalogHttpClient.Snapshot snapshot = snapshots.get(binding);
        if (snapshot != null) for (String id : snapshot.models().keySet()) ids.put(id, true);
        for (Map.Entry<ModelKey, OwnerRecord> row : overrides.entrySet()) if (row.getKey().binding().equals(binding) && !row.getValue().fields().isEmpty()) ids.put(row.getKey().modelId(), true);
        List<View> views = new ArrayList<>(); for (String id : ids.keySet()) views.add(get(new ModelKey(binding, id), maxCatalogAgeMillis));
        return List.copyOf(views);
    }

    private void requireActive(ModelBinding binding) {
        if (!binding.equals(active.get(binding.connectionId()))) throw new IllegalStateException("model_binding_changed");
    }

    private long advance(String connectionId) {
        long next = Math.addExact(generations.getOrDefault(connectionId, 0L), 1); generations.put(connectionId, next); return next;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_CACHE_BYTES) throw new IllegalArgumentException();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            JSONTokener tokener = new JSONTokener(text);
            Object raw = tokener.nextValue();
            if (!(raw instanceof JSONObject root) || tokener.nextClean() != 0 || ModelFacts.positiveInteger(root.get("schema")) != 1) throw new IllegalArgumentException();
            Map<ModelBinding, CatalogHttpClient.Snapshot> loadedSnapshots = new LinkedHashMap<>();
            JSONArray saved = root.getJSONArray("snapshots");
            for (int n = 0; n < saved.length(); n++) {
                JSONObject row = saved.getJSONObject(n); ModelBinding binding = ModelBinding.read(row.getJSONObject("binding"));
                long fetched = ModelFacts.positiveInteger(row.get("fetchedAtMillis"));
                Map<String, ModelFacts> models = new LinkedHashMap<>(); JSONArray entries = row.getJSONArray("models");
                if (entries.length() > 10000) throw new IllegalArgumentException();
                for (int m = 0; m < entries.length(); m++) {
                    JSONObject model = entries.getJSONObject(m);
                    ModelFacts facts = new ModelFacts(ModelFacts.readFields(model.getJSONObject("facts"), Source.PROVIDER_CATALOG));
                    for (Field field : Field.values()) if (facts.fact(field).observedAtMillis < 1 || facts.fact(field).observedAtMillis > fetched) throw new IllegalArgumentException();
                    if (models.put(ModelBinding.modelId(model.getString("id")), facts) != null) throw new IllegalArgumentException();
                }
                Map<String,ModelPrice> prices=new LinkedHashMap<>();JSONArray storedPrices=row.optJSONArray("prices");
                for(int nPrice=0;storedPrices!=null&&nPrice<storedPrices.length();nPrice++){ModelPrice price=ModelPrice.fromJson(storedPrices.getJSONObject(nPrice));
                    if(!price.binding.equals(binding)||price.source!=ModelPrice.Source.PROVIDER_CATALOG||price.observedAt>fetched||!models.containsKey(price.model)||prices.put(price.model,price)!=null)throw new IllegalArgumentException();}
                Map<String,ModelEndpointSupport> endpointSupport=new LinkedHashMap<>();JSONObject storedEndpoints=row.optJSONObject("endpointSupport");
                if(storedEndpoints!=null){java.util.Iterator<String> ids=storedEndpoints.keys();while(ids.hasNext()){String id=ids.next();ModelEndpointSupport value=ModelEndpointSupport.fromJson(storedEndpoints.getJSONObject(id));validateEndpointSupport(binding,models,id,value,1,fetched);endpointSupport.put(id,value);}}
                if (loadedSnapshots.put(binding, new CatalogHttpClient.Snapshot(binding, fetched, models,prices,endpointSupport)) != null) throw new IllegalArgumentException();
            }
            Map<ModelKey, OwnerRecord> loadedOverrides = new LinkedHashMap<>(); JSONArray owners = root.getJSONArray("overrides");
            for (int n = 0; n < owners.length(); n++) {
                JSONObject row = owners.getJSONObject(n); ModelKey key = new ModelKey(ModelBinding.read(row.getJSONObject("binding")), row.getString("modelId"));
                Map<Field, Fact<?>> fields = ModelFacts.readFields(row.getJSONObject("fields"), Source.OWNER_OVERRIDE);
                for (Fact<?> fact : fields.values()) if (fact.observedAtMillis < 1) throw new IllegalArgumentException();
                if (loadedOverrides.put(key, new OwnerRecord(ModelFacts.positiveInteger(row.get("revision")), Collections.unmodifiableMap(fields),row.optJSONObject("price")==null?null:ownerPrice(row.getJSONObject("price"),key))) != null) throw new IllegalArgumentException();
            }
            snapshots = loadedSnapshots; overrides = loadedOverrides;
            // Active bindings deliberately start empty. The current native host must bind again.
        } catch (Exception invalid) { throw new CatalogException(CatalogException.Code.CACHE_INVALID); }
    }

    private static ModelPrice ownerPrice(JSONObject saved,ModelKey key)throws Exception{
        ModelPrice price=ModelPrice.fromJson(saved);if(price.source!=ModelPrice.Source.OWNER_REVIEWED||!price.binding.equals(key.binding())||!price.model.equals(key.modelId()))throw new SecurityException("owner_price_cache_identity_mismatch");return price;
    }

    private static void validateEndpointSupport(ModelBinding binding,Map<String,ModelFacts> models,String id,ModelEndpointSupport declared,long earliest,long latest){
        if(!models.containsKey(id)||declared.observedAtMillis()<earliest||declared.observedAtMillis()>latest
                ||!declared.sourceRef().startsWith(binding.modelsEndpoint().toASCIIString()+"#page=")||!declared.sourceRef().endsWith("/supported_endpoints"))throw new IllegalArgumentException("refresh_endpoint_provenance_mismatch");
    }

    private void save(Map<ModelBinding, CatalogHttpClient.Snapshot> snapshots, Map<ModelKey, OwnerRecord> owners) throws IOException {
        final byte[] bytes;
        try {
            JSONArray saved = new JSONArray(), overrides = new JSONArray();
            for (CatalogHttpClient.Snapshot snapshot : snapshots.values()) {
                JSONArray models = new JSONArray();
                for (Map.Entry<String, ModelFacts> row : snapshot.models().entrySet()) models.put(new JSONObject().put("id", row.getKey()).put("facts", row.getValue().toJson()));
                JSONArray prices=new JSONArray();for(ModelPrice price:snapshot.prices().values())prices.put(price.toJson());
                JSONObject endpoints=new JSONObject();for(var entry:snapshot.endpointSupport().entrySet())endpoints.put(entry.getKey(),entry.getValue().toJson());
                saved.put(new JSONObject().put("binding", snapshot.binding().json()).put("fetchedAtMillis", snapshot.fetchedAtMillis()).put("models", models).put("prices",prices).put("endpointSupport",endpoints));
            }
            for (Map.Entry<ModelKey, OwnerRecord> row : owners.entrySet()) overrides.put(new JSONObject().put("binding", row.getKey().binding().json())
                    .put("modelId", row.getKey().modelId()).put("revision", row.getValue().revision()).put("fields", ModelFacts.fieldsJson(row.getValue().fields())).put("price",row.getValue().price()==null?JSONObject.NULL:row.getValue().price().toJson()));
            bytes = new JSONObject().put("schema", 1).put("snapshots", saved).put("overrides", overrides).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception invalid) { throw new CatalogException(CatalogException.Code.CACHE_INVALID); }
        if (bytes.length > MAX_CACHE_BYTES) throw new CatalogException(CatalogException.Code.TOO_LARGE);
        Path parent = file.getParent(); Files.createDirectories(parent); Path temporary = Files.createTempFile(parent, "model-catalog-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
