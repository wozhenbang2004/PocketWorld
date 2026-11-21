package dev.pocketengine.features.attachments.creation;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/** Native-only private draft data. No task, Book entry, preview, or generation authority. */
public final class OwnerStandaloneDocumentDraftStore {
    public static final int MAX_DRAFTS = 8, MAX_TOTAL_BYTES = 384 * 1024;
    private static final int MAX_RECORD_BYTES = 64 * 1024, MAX_HEADER_BYTES = 2048;
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final String DIRECTORY = "owner-standalone-document-drafts-v1";
    private static final String SCHEMA = "pocketworld.owner-standalone-document-draft.v1";
    private static final String LINKED_SCHEMA = "pocketworld.owner-standalone-document-draft.v2";
    private static final Set<String> HEADER = Set.of("schema", "owner", "space", "id", "revision", "sha256", "format", "name", "savedAt", "bytes");
    private static final Set<String> LINKED_HEADER = Set.of("schema", "owner", "space", "id", "revision", "sha256", "format", "name", "savedAt", "bytes", "revisionOf");
    private static final Set<String> REVISION_SOURCE = Set.of("parentArtifactId", "parentSha256", "editableSourceId", "editableSourceSha256");
    private static final String ENTRY = ".draft", STAGED = ".pending";
    private static final Object LOCK = new Object(); // Only the private owner UI process may mount this store.
    private final Context app;

    @FunctionalInterface public interface Commit { void enter() throws IOException; }
    /** Supplied by the live private owner route, never exposed or stored. The short
     * atCommit callback is synchronous and fenced against pause/leave; no reentry into this store. */
    public interface OwnerWindow {
        boolean current();
        void atCommit(Commit mutation) throws IOException;
    }

    public enum Format {
        WORD("docx"), PDF("pdf");
        public final String value;
        Format(String value) { this.value = value; }
    }
    /** Deliberately allows empty/incomplete Word/PDF input. DocumentDraft still gates generation. */
    public record Fields(Format format, String name, String title, String body, int fontPoints) {
        public Fields {
            Objects.requireNonNull(format);
            validateText(name, 80);
            validateText(title, 200);
            validateText(body, DocumentDraft.MAX_BODY);
            if (!Set.of(10, 11, 12, 14, 16).contains(fontPoints))
                throw new IllegalArgumentException("草稿字号不受支持");
        }
    }
    public record Pin(UUID draftId, long revision, String sha256) {
        public Pin {
            Objects.requireNonNull(draftId);
            if (revision <= 0 || revision > MAX_REVISION || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("原草稿版本无效");
        }
    }
    /** Exact native lineage locator only. This store cannot authorize or read either Book object. */
    public record RevisionSource(UUID parentArtifactId, String parentSha256, UUID editableSourceId, String editableSourceSha256) {
        public RevisionSource {
            if (parentArtifactId == null || editableSourceId == null || parentArtifactId.equals(editableSourceId)
                    || !digest(parentSha256) || !digest(editableSourceSha256))
                throw new IllegalArgumentException("原文件与可编辑源的版本关联无效");
        }
    }
    /** Header only, not body readback or permission to edit. A link must be verified against the selected complete draft before use. */
    public record Metadata(Pin pin, Format format, String name, long savedAt, int bytes, RevisionSource revisionSource) {
        public Metadata(Pin pin, Format format, String name, long savedAt, int bytes) {
            this(pin, format, name, savedAt, bytes, null);
        }
    }
    public record Draft(Metadata metadata, Fields fields) {}
    public record Catalog(List<Metadata> entries, boolean metadataComplete) {
        public Catalog { entries = List.copyOf(entries); }
    }
    /** SAVED is this call's synced publication and readback. VERIFIED is a positive
     * observation of the committed version, not a receipt for a previous write attempt.
     * MISSING is absence at this read, not proof another operation never existed. */
    public enum State { SAVED, VERIFIED, MISSING, CONFLICT, UNKNOWN, QUOTA, NO_EDIT, REMOVED }
    public record Outcome(State state, Draft draft) {
        private static Outcome only(State state) { return new Outcome(state, null); }
    }
    /** First write only. An old seed cannot recreate an already removed record. */
    public static final class Initial {
        private final OwnerStandaloneDocumentDraftStore issuer;
        private final OwnerWindow window;
        private final RevisionSource revisionSource;
        private final UUID id = UUID.randomUUID();
        private boolean entered;
        private Initial(OwnerStandaloneDocumentDraftStore issuer, OwnerWindow window, RevisionSource revisionSource) {
            this.issuer = issuer;
            this.window = window;
            this.revisionSource = revisionSource;
        }
    }
    private static final Fields EMPTY = new Fields(Format.WORD, "文档", "", "", 12);
    private record Directory(Path path, Object key) {}
    private record Root(Directory data, Directory noBackup, Path path, BasicFileAttributes identity) {}
    private record Stored(Metadata metadata, Fields fields, BasicFileAttributes identity) {
        Draft draft() { return new Draft(metadata, Objects.requireNonNull(fields)); }
    }
    private record Scan(List<UUID> committed, Set<UUID> ids, long bytes, boolean bounded, boolean clean) {}
    @FunctionalInterface private interface OwnerIO<T> { T run() throws IOException, JSONException; }
    private static final Catalog UNREADABLE_CATALOG = new Catalog(List.of(), false);
    private static final Outcome UNKNOWN = Outcome.only(State.UNKNOWN);

    public OwnerStandaloneDocumentDraftStore(Context context) { app = context.getApplicationContext(); }

    /** Explicit new editor generation; neither creates a directory nor writes a blank draft. */
    public Initial initialize(OwnerWindow window) {
        requireOwner(window);
        return new Initial(this, window, null);
    }

    /** Native-only seed. Its owner is still responsible for proving the exact Book pair before invoking this. No IO. */
    public Initial initializeRevision(OwnerWindow window, RevisionSource source) {
        requireOwner(window);
        return new Initial(this, window, Objects.requireNonNull(source));
    }

    /** Native locator for these exact proposed first bytes, not evidence of a write.
     * Capture it before dispatch and use only an explicit fresh read to check an uncertain
     * result. Does not touch storage, advance the seed or extend the original window. */
    public Pin expectedFirstPin(Initial original, Fields fields) throws IOException, JSONException {
        OwnerWindow window = initialWindow(original);
        requireOwner(window);
        Objects.requireNonNull(fields);
        if (original.revisionSource == null && fields.equals(EMPTY)) throw new IllegalArgumentException("原新建页还没有修改");
        byte[] content = encode(fields);
        try {
            Pin pin = firstPin(original, content);
            requireOwner(window);
            return pin;
        } finally { Arrays.fill(content, (byte) 0); }
    }

    public Catalog catalog(OwnerWindow window) {
        return ownerIO(window, UNREADABLE_CATALOG, () -> {
            Root root = root();
            if (root.identity == null) {
                sameRoot(root);
                return new Catalog(List.of(), true);
            }
            Scan scan = scan(root);
            List<Metadata> entries = new ArrayList<>();
            boolean complete = scan.clean;
            for (UUID id : scan.committed) {
                if (entries.size() == MAX_DRAFTS) { complete = false; break; }
                requireOwner(window);
                try {
                    Stored row = load(root, id, null, window);
                    if (row == null) complete = false;
                    else entries.add(row.metadata);
                } catch (IOException | JSONException invalid) { complete = false; }
            }
            sameRoot(root);
            entries.sort(Comparator.comparingLong(Metadata::savedAt).reversed()
                .thenComparing(m -> m.pin.draftId.toString()));
            return new Catalog(entries, complete);
        });
    }

    public Outcome read(Pin expected, OwnerWindow window) {
        Objects.requireNonNull(expected);
        return ownerIO(window, UNKNOWN, () -> {
            Root root = root();
            Stored row = load(root, expected.draftId, expected, window);
            if (row != null && row.fields != null) return new Outcome(State.VERIFIED, row.draft());
            if (present(root, expected.draftId, STAGED)) return UNKNOWN;
            return Outcome.only(row == null ? State.MISSING : State.CONFLICT);
        });
    }

    public Outcome firstWrite(Initial original, Fields fields) {
        Objects.requireNonNull(fields);
        OwnerWindow window = initialWindow(original);
        return ownerIO(window, UNKNOWN, () -> {
            byte[] content = encode(fields);
            try {
                Root root = root();
                Pin pin = firstPin(original, content);
                Stored existing = load(root, original.id, original.entered ? pin : null, window);
                if (existing != null) return original.entered && fields.equals(existing.fields)
                        && Objects.equals(original.revisionSource, existing.metadata.revisionSource)
                    ? new Outcome(State.VERIFIED, existing.draft()) : Outcome.only(State.CONFLICT);
                if (original.entered || present(root, original.id, STAGED)) return UNKNOWN;
                if (original.revisionSource == null && fields.equals(EMPTY)) return Outcome.only(State.NO_EDIT);
                return save(ensureRoot(root, window), pin, fields, original.revisionSource, content, null, original, window);
            } finally { Arrays.fill(content, (byte) 0); }
        });
    }

    private OwnerWindow initialWindow(Initial original) {
        if (original == null || original.issuer != this) throw new SecurityException("不是这次私人新建文档");
        return original.window;
    }

    private static Pin firstPin(Initial original, byte[] content) {
        return new Pin(original.id, 1, contentDigest(original.revisionSource, content));
    }

    public Outcome write(Pin expected, Fields fields, OwnerWindow window) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(fields);
        return ownerIO(window, UNKNOWN, () -> {
            Root root = root();
            if (present(root, expected.draftId, STAGED)) return UNKNOWN;
            Stored row = load(root, expected.draftId, expected, window);
            if (row == null) return Outcome.only(State.MISSING);
            if (row.fields == null) return Outcome.only(State.CONFLICT);
            if (fields.equals(row.fields)) return new Outcome(State.VERIFIED, row.draft());
            if (expected.revision == MAX_REVISION) return Outcome.only(State.QUOTA);
            byte[] content = encode(fields);
            try {
                // The current verified row fixes lineage; writing fields never accepts a replacement pair.
                RevisionSource source = row.metadata.revisionSource;
                Pin next = new Pin(expected.draftId, expected.revision + 1, contentDigest(source, content));
                return save(root, next, fields, source, content, row.identity, null, window);
            } finally { Arrays.fill(content, (byte) 0); }
        });
    }

    /** Call only after a native explicit discard of this displayed exact pin. */
    public Outcome remove(Pin expected, OwnerWindow window) {
        Objects.requireNonNull(expected);
        return ownerIO(window, UNKNOWN, () -> {
            Root root = root();
            if (present(root, expected.draftId, STAGED)) return UNKNOWN;
            Stored row = load(root, expected.draftId, expected, window);
            if (row == null) return Outcome.only(State.MISSING);
            if (row.fields == null) return Outcome.only(State.CONFLICT);
            Path path = path(root, expected.draftId, ENTRY);
            atCommit(window, () -> {
                if (present(root, expected.draftId, STAGED)) throw new IOException("原草稿仍有未核对的写入");
                requireSame(row.identity, leaf(path));
                Files.delete(path);
            });
            syncNamespace(root);
            return leaf(path) == null ? Outcome.only(State.REMOVED) : UNKNOWN;
        });
    }

    private Outcome save(Root root, Pin pin, Fields fields, RevisionSource source, byte[] content,
                         BasicFileAttributes previous, Initial initial, OwnerWindow window) throws IOException, JSONException {
        long now = System.currentTimeMillis();
        if (now <= 0 || now > MAX_REVISION) return UNKNOWN;
        JSONObject info = new JSONObject().put("schema", source == null ? SCHEMA : LINKED_SCHEMA).put("owner", "owner").put("space", "private")
            .put("id", pin.draftId.toString()).put("revision", pin.revision).put("sha256", pin.sha256)
            .put("format", fields.format.value).put("name", fields.name).put("savedAt", now).put("bytes", content.length);
        if (source != null) info.put("revisionOf", encodeRevisionSource(source));
        byte[] header = utf8(info.toString());
        int size = header.length + 1 + content.length;
        if (header.length > MAX_HEADER_BYTES || size > MAX_RECORD_BYTES) return Outcome.only(State.QUOTA);
        Scan scan = scan(root);
        if (!scan.bounded) return UNKNOWN;
        // Reserve the full staging copy before writing; even a crash leaving both copies
        // must remain within the physical budget. Never evict another draft to make room.
        if ((!scan.ids.contains(pin.draftId) && scan.ids.size() >= MAX_DRAFTS)
                || scan.bytes + size > MAX_TOTAL_BYTES)
            return Outcome.only(State.QUOTA);
        Path target = path(root, pin.draftId, ENTRY), staged = path(root, pin.draftId, STAGED);
        sameRoot(root);
        requireSame(previous, leaf(target));
        requireOwner(window);
        if (initial != null) initial.entered = true;
        // Only this live CREATE_NEW attempt may roll back, and only before entry to rename.
        // Never adopt or delete a staged file found on a later call.
        AtomicBoolean notEntered = new AtomicBoolean(true);
        BasicFileAttributes created = null;
        BasicFileAttributes ready;
        try {
            try (FileChannel out = FileChannel.open(staged, Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                BasicFileAttributes origin = leaf(staged);
                if (origin == null || origin.size() != 0) throw new IOException("本机草稿暂存文件不可核对");
                created = origin;
                requireOwner(window);
                put(out, header);
                put(out, new byte[]{'\n'});
                put(out, content);
                out.force(true);
            }
            ready = leaf(staged);
            if (ready == null || !created.fileKey().equals(ready.fileKey()) || ready.size() != size)
                throw new IOException("本机草稿暂存文件已变化");
            atCommit(window, () -> {
                sameRoot(root);
                requireSame(previous, leaf(target));
                requireSame(ready, leaf(staged));
                if (!notEntered.compareAndSet(true, false)) throw new IOException("本次暂存已结束");
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            });
            if (notEntered.get()) throw new IOException("原草稿发布未进入");
        } catch (IOException | RuntimeException failure) {
            if (notEntered.compareAndSet(true, false) && created != null) try {
                abandonOwnStage(root, staged, target, created, previous, size);
                if (initial != null) initial.entered = false;
            } catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        syncNamespace(root);
        Stored actual = load(root, pin.draftId, pin, window);
        if (actual == null || !fields.equals(actual.fields) || !Objects.equals(source, actual.metadata.revisionSource)) return UNKNOWN;
        requireSame(ready, actual.identity);
        return new Outcome(State.SAVED, actual.draft());
    }

    private Root root() throws IOException {
        File dataDir = app.getDataDir();
        File noBackup = app.getNoBackupFilesDir();
        if (dataDir == null || noBackup == null) throw new IOException("本机私有存储不可核对");
        // Android may alias ancestors of the supplied paths, not the final app-data
        // or noBackup directory. Recheck both actual directory identities after selection.
        Directory data = directory(canonicalParentChild(dataDir));
        Path base = canonicalParentChild(noBackup);
        if (!data.path.equals(base.getParent())) throw new IOException("本机私有存储位置不同");
        Directory parent = directory(base);
        Path dir = parent.path.resolve(DIRECTORY);
        BasicFileAttributes identity = attributes(dir);
        if (identity != null) requireDirectory(dir, identity);
        Root root = new Root(data, parent, dir, identity);
        sameRoot(root);
        return root;
    }
    private static Path canonicalParentChild(File supplied) throws IOException {
        Path raw = supplied.toPath().toAbsolutePath();
        if (raw.getParent() == null || raw.getFileName() == null) throw new IOException("本机私有存储位置不同");
        Path real = raw.getParent().toRealPath().resolve(raw.getFileName());
        if (!real.equals(real.normalize())) throw new IOException("本机私有存储位置不同");
        return real;
    }

    private Root ensureRoot(Root original, OwnerWindow window) throws IOException {
        if (original.identity != null) return original;
        atCommit(window, () -> {
            sameRoot(original);
            Files.createDirectory(original.path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        });
        BasicFileAttributes created = requireDirectory(original.path, attributes(original.path));
        Root ready = new Root(original.data, original.noBackup, original.path, created);
        sameRoot(ready);
        return ready;
    }

    private static Scan scan(Root root) throws IOException {
        sameRoot(root);
        List<UUID> published = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        boolean bounded = true, clean = true;
        long bytes = 0;
        int count = 0;
        try (var entries = Files.newDirectoryStream(root.path)) {
            for (Path file : entries) {
                if (++count > MAX_DRAFTS * 2) { bounded = false; break; }
                String name = file.getFileName().toString();
                boolean staged = name.endsWith(STAGED);
                String suffix = staged ? STAGED : ENTRY;
                UUID id = name.endsWith(suffix) ? uuid(name.substring(0, name.length() - suffix.length())) : null;
                if (id == null) { bounded = false; continue; }
                BasicFileAttributes stat;
                try { stat = leaf(file); } catch (IOException unconfirmed) { bounded = false; continue; }
                if (stat == null || stat.size() < 0 || stat.size() > MAX_RECORD_BYTES) {
                    bounded = false; continue;
                }
                ids.add(id);
                bytes += stat.size();
                if (staged) clean = false; else published.add(id);
            }
        } catch (DirectoryIteratorException failure) { throw failure.getCause(); }
        sameRoot(root);
        return new Scan(published, ids, bytes, bounded && ids.size() <= MAX_DRAFTS,
            bounded && clean && ids.size() <= MAX_DRAFTS && bytes <= MAX_TOTAL_BYTES);
    }

    private static Stored load(Root root, UUID id, Pin expected, OwnerWindow window) throws IOException, JSONException {
        requireOwner(window);
        sameRoot(root);
        Path file = path(root, id, ENTRY);
        BasicFileAttributes before = leaf(file);
        if (before == null) return null;
        if (before.size() <= 0 || before.size() > MAX_RECORD_BYTES)
            throw new IOException("原草稿大小或类型不可核对");
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            int next;
            while ((next = in.read()) != -1 && next != '\n') {
                if (prefix.size() == MAX_HEADER_BYTES) throw new IOException("草稿头超限");
                prefix.write(next);
            }
            JSONObject info = new JSONObject(decode(prefix.toByteArray()));
            Object schema = info.opt("schema");
            boolean linked = LINKED_SCHEMA.equals(schema);
            if (next != '\n' || (!linked && !SCHEMA.equals(schema)) || !exactFields(info, linked ? LINKED_HEADER : HEADER)
                    || !"owner".equals(info.opt("owner")) || !"private".equals(info.opt("space")) || !id.toString().equals(info.opt("id")))
                throw new IOException("草稿归属或记录版本不可核对");
            RevisionSource source = linked ? decodeRevisionSource(info.getJSONObject("revisionOf")) : null;
            int bytes = (int) number(info, "bytes", 1, MAX_RECORD_BYTES);
            if (before.size() != prefix.size() + 1L + bytes) throw new IOException("原草稿字节不完整");
            Pin pin = new Pin(id, number(info, "revision", 1, MAX_REVISION), text(info, "sha256"));
            Format format = format(info);
            String name = text(info, "name");
            validateText(name, 80);
            Metadata metadata = new Metadata(pin, format, name, number(info, "savedAt", 1, MAX_REVISION), bytes, source);
            Fields body = null;
            // Catalog and a stale expected revision read headers only.
            if (pin.equals(expected)) {
                requireOwner(window);
                byte[] data = new byte[bytes];
                try {
                    new DataInputStream(in).readFully(data);
                    if (in.read() != -1 || !pin.sha256.equals(contentDigest(source, data))) throw new IOException("原草稿字节未通过核对");
                    body = decodeFields(data);
                    if (body.format != format || !body.name.equals(name)) throw new IOException("原草稿内容与目录不符");
                } finally { Arrays.fill(data, (byte) 0); }
            }
            requireSame(before, leaf(file));
            sameRoot(root);
            return new Stored(metadata, body, before);
        } catch (IllegalArgumentException invalid) { throw new IOException("原草稿记录不可核对", invalid); }
    }

    private static Path path(Root root, UUID id, String suffix) { return root.path.resolve(id.toString() + suffix); }
    private static BasicFileAttributes attributes(Path file) throws IOException {
        try { return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return null; }
    }
    private static BasicFileAttributes leaf(Path path) throws IOException {
        BasicFileAttributes actual = attributes(path);
        if (actual == null) return null;
        if (!actual.isRegularFile() || actual.isSymbolicLink() || actual.fileKey() == null || !path.equals(path.toRealPath()))
            throw new IOException("本机草稿不是独立普通记录");
        Object links;
        try { links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS); }
        catch (IllegalArgumentException | UnsupportedOperationException unavailable) {
            throw new IOException("本机草稿链接身份不可核对", unavailable);
        }
        if (!(links instanceof Number count) || count.longValue() != 1) throw new IOException("本机草稿不是独立普通记录");
        return actual;
    }
    private static boolean present(Root root, UUID id, String suffix) throws IOException {
        sameRoot(root);
        return leaf(path(root, id, suffix)) != null;
    }
    private static BasicFileAttributes requireDirectory(Path path, BasicFileAttributes attributes) throws IOException {
        if (attributes == null || !attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null
                || !path.equals(path.toRealPath())) throw new IOException("原草稿目录不可核对");
        return attributes;
    }
    private static Directory directory(Path path) throws IOException {
        return new Directory(path, requireDirectory(path, attributes(path)).fileKey());
    }
    private static void sameRoot(Root root) throws IOException {
        if (!root.data.equals(directory(root.data.path)) || !root.noBackup.equals(directory(root.noBackup.path)))
            throw new IOException("原本机私有存储已变化");
        BasicFileAttributes after = attributes(root.path);
        if (root.identity == null ? after != null : !root.identity.fileKey().equals(requireDirectory(root.path, after).fileKey()))
            throw new IOException("原草稿目录已变化");
    }
    private static void requireSame(BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (before == null ? after != null : after == null || !after.isRegularFile() || before.size() != after.size()
                || !Objects.equals(before.fileKey(), after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("原草稿已变化");
    }
    private static void forceDir(Path dir) throws IOException {
        try (FileChannel out = FileChannel.open(dir, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { out.force(true); }
    }
    private static void syncNamespace(Root root) throws IOException {
        if (root.identity == null) throw new IOException("原草稿目录不可核对");
        sameRoot(root);
        forceDir(root.path);
        sameRoot(root);
        forceDir(root.noBackup.path);
        sameRoot(root);
        forceDir(root.data.path);
        sameRoot(root);
    }
    private static void abandonOwnStage(Root root, Path staged, Path target, BasicFileAttributes created,
                                       BasicFileAttributes previous, int size) throws IOException {
        sameRoot(root);
        requireSame(previous, leaf(target)); // Do not touch a draft or attempt whose original head changed.
        BasicFileAttributes current = leaf(staged);
        if (current == null || !created.fileKey().equals(current.fileKey()) || current.size() > size)
            throw new IOException("本次未发布的暂存身份不可核对");
        Files.delete(staged);
        syncNamespace(root);
        requireSame(previous, leaf(target));
        if (leaf(staged) != null) throw new IOException("本次未发布的暂存尚未确认清除");
    }
    private static void put(FileChannel out, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) if (out.write(buffer) <= 0) throw new IOException("草稿写入没有进展");
    }
    private static void requireOwner(OwnerWindow owner) {
        if (owner == null || !owner.current()) throw new SecurityException("原私人文档草稿窗口已关闭");
    }
    private static <T> T ownerIO(OwnerWindow window, T unknown, OwnerIO<T> read) {
        requireOwner(window);
        synchronized (LOCK) {
            requireOwner(window);
            T result;
            try { result = read.run(); }
            catch (IOException | JSONException failure) { result = unknown; }
            requireOwner(window);
            return result;
        }
    }
    private static void atCommit(OwnerWindow window, Commit mutation) throws IOException {
        window.atCommit(() -> { requireOwner(window); mutation.enter(); });
    }
    private static JSONObject encodeRevisionSource(RevisionSource source) throws JSONException {
        return new JSONObject().put("parentArtifactId", source.parentArtifactId.toString()).put("parentSha256", source.parentSha256)
            .put("editableSourceId", source.editableSourceId.toString()).put("editableSourceSha256", source.editableSourceSha256);
    }
    private static RevisionSource decodeRevisionSource(JSONObject source) throws IOException {
        if (!exactFields(source, REVISION_SOURCE)) throw new IOException("原版本关联字段不可核对");
        return new RevisionSource(uuid(text(source, "parentArtifactId")), text(source, "parentSha256"),
            uuid(text(source, "editableSourceId")), text(source, "editableSourceSha256"));
    }
    private static byte[] encode(Fields fields) throws IOException, JSONException {
        return utf8(new JSONObject().put("format", fields.format.value).put("name", fields.name)
            .put("title", fields.title).put("body", fields.body).put("fontPoints", fields.fontPoints).toString());
    }
    private static Fields decodeFields(byte[] bytes) throws IOException, JSONException {
        JSONObject info = new JSONObject(decode(bytes));
        if (!exactFields(info, Set.of("format","name","title","body","fontPoints"))) throw new IOException("草稿含未知字段");
        return new Fields(format(info), text(info, "name"), text(info, "title"), text(info, "body"),
            (int) number(info, "fontPoints", 10, 16));
    }
    private static Format format(JSONObject info) throws IOException {
        for (Format kind : Format.values()) if (kind.value.equals(info.opt("format"))) return kind;
        throw new IOException("草稿文件格式不可核对");
    }
    private static String text(JSONObject info, String name) throws IOException {
        if (info.opt(name) instanceof String value) return value;
        throw new IOException("草稿文字字段不可核对");
    }
    private static long number(JSONObject info, String name, long min, long max) throws IOException {
        Object value = info.opt(name);
        if (value instanceof Integer || value instanceof Long) {
            long n = ((Number) value).longValue();
            if (n >= min && n <= max) return n;
        }
        throw new IOException("草稿数字字段不可核对");
    }
    private static boolean exactFields(JSONObject info, Set<String> fields) {
        if (info.length() != fields.size()) return false;
        for (String field : fields) if (!info.has(field)) return false;
        return true;
    }
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); return id.toString().equals(value) ? id : null; }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static void validateText(String value, int max) {
        if (value == null || value.length() > max || value.codePoints().anyMatch(c ->
            (c >= 0xD800 && c <= 0xDFFF) || (c < 32 && c != '\t' && c != '\r' && c != '\n')))
            throw new IllegalArgumentException("草稿文字超限或含无效字符");
    }
    private static byte[] utf8(String value) throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        return out;
    }
    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String contentDigest(RevisionSource source, byte[] bytes) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // V1 is exactly SHA-256(payload). V2 seals the typed tuple without duplicating it in the body.
            if (source != null) hash.update((LINKED_SCHEMA + "\0" + source.parentArtifactId + "\0" + source.parentSha256 + "\0"
                + source.editableSourceId + "\0" + source.editableSourceSha256 + "\0").getBytes(StandardCharsets.US_ASCII));
            StringBuilder value = new StringBuilder(64);
            for (byte b : hash.digest(bytes))
                value.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
