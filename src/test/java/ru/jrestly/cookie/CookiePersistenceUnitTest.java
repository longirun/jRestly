package ru.jrestly.cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.jrestly.JRestlyClient;
import ru.jrestly.SimpleModuleInfo;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for persistent cookie storage (ADR-0039): RFC 6265
 * {@code CookieCodec} formatting/parse/filtering, the file-backed persister
 * save/load/clear and validation, {@code PersistentCookieStore} auto/manual
 * modes with preload/reload, builder wiring and concurrent access. No network.
 */
@DisplayName("ADR-0039: Cookie Persistence & PersistentCookieStore Unit Tests")
class CookiePersistenceUnitTest {

    @Test
    @DisplayName("CookieCodec formats cookie with all attributes according to RFC 6265")
    void cookieCodecFormatsAllAttributes() {
        HttpCookie cookie = new HttpCookie("sid", "abc-123");
        cookie.setDomain("api.example.com");
        cookie.setPath("/v1");
        cookie.setMaxAge(3600);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setVersion(1);

        String formatted = CookieCodec.format(cookie);

        assertTrue(formatted.startsWith("sid=abc-123"));
        assertTrue(formatted.contains("; Domain=api.example.com"));
        assertTrue(formatted.contains("; Path=/v1"));
        assertTrue(formatted.contains("; Max-Age=3600"));
        assertTrue(formatted.contains("; Secure"));
        assertTrue(formatted.contains("; HttpOnly"));
        assertTrue(formatted.contains("; Version=1"));

        // Edge-cases: null value, blank domain, blank path, negative maxAge
        HttpCookie edgeCookie = new HttpCookie("empty-attr", null);
        edgeCookie.setDomain("   ");
        edgeCookie.setPath("   ");
        edgeCookie.setMaxAge(-1);
        edgeCookie.setVersion(0);
        String edgeFormatted = CookieCodec.format(edgeCookie);
        assertEquals("empty-attr=", edgeFormatted);
    }

    @Test
    @DisplayName("CookieCodec round-trip preserves cookie attributes")
    void cookieCodecRoundTrip() throws IOException {
        HttpCookie c1 = new HttpCookie("auth", "token999");
        c1.setDomain("auth.server.com");
        c1.setPath("/auth");
        c1.setMaxAge(7200);
        c1.setSecure(true);
        c1.setHttpOnly(true);

        HttpCookie c2 = new HttpCookie("theme", "dark");
        c2.setDomain("ui.server.com");
        c2.setPath("/");

        StringWriter writer = new StringWriter();
        CookieCodec.write(writer, List.of(c1, c2));

        String serialized = writer.toString();
        assertTrue(serialized.contains("# jRestly Persistent Cookie Storage"));
        assertTrue(serialized.contains("auth=token999"));
        assertTrue(serialized.contains("theme=dark"));

        List<HttpCookie> read = CookieCodec.read(new StringReader(serialized));
        assertEquals(2, read.size());

        HttpCookie readAuth = read.stream().filter(c -> "auth".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("token999", readAuth.getValue());
        assertEquals("auth.server.com", readAuth.getDomain());
        assertEquals("/auth", readAuth.getPath());
        assertTrue(readAuth.getSecure());
        assertTrue(readAuth.isHttpOnly());

        HttpCookie readTheme = read.stream().filter(c -> "theme".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("dark", readTheme.getValue());
        assertEquals("ui.server.com", readTheme.getDomain());
        assertEquals("/", readTheme.getPath());
    }

    @Test
    @DisplayName("CookieCodec parse skips invalid, blank, comments and expired cookies")
    void cookieCodecParseFiltering() {
        assertTrue(CookieCodec.parse(null).isEmpty());
        assertTrue(CookieCodec.parse("   ").isEmpty());
        assertTrue(CookieCodec.parse("# this is a comment").isEmpty());
        assertTrue(CookieCodec.parse("malformed %$#^! cookie line").isEmpty());

        // expired cookie (Max-Age=0 or negative)
        List<HttpCookie> expired = CookieCodec.parse("expired=val; Max-Age=0");
        assertTrue(expired.isEmpty());
    }

    @Test
    @DisplayName("CookieCodec write and read null safety")
    void cookieCodecNullSafety() {
        assertThrows(NullPointerException.class, () -> CookieCodec.format(null));
        assertThrows(NullPointerException.class, () -> CookieCodec.write(null, List.of()));
        assertThrows(NullPointerException.class, () -> CookieCodec.write(new StringWriter(), null));
        assertThrows(NullPointerException.class, () -> CookieCodec.read(null));
    }

    @Test
    @DisplayName("FileCookiePersister saves, loads and clears cookies atomically")
    void fileCookiePersisterSavesLoadsClears(@TempDir Path tempDir) {
        Path file = tempDir.resolve("nested/dir/session.cookies");
        FileCookiePersister persister = FileCookiePersister.of(file);
        assertEquals(file, persister.getPath());
        assertEquals(StandardCharsets.UTF_8, persister.getCharset());

        // Initial load on non-existent file
        assertTrue(persister.loadAll().isEmpty());

        HttpCookie c = new HttpCookie("sess", "secret42");
        c.setDomain("example.org");
        c.setPath("/");
        c.setMaxAge(1800);

        persister.saveAll(List.of(c));
        assertTrue(Files.exists(file));

        List<HttpCookie> loaded = persister.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("sess", loaded.get(0).getName());
        assertEquals("secret42", loaded.get(0).getValue());

        persister.clear();
        assertFalse(Files.exists(file));
        assertTrue(persister.loadAll().isEmpty());
    }

    @Test
    @DisplayName("FileCookiePersister constructor, custom charset, and null checks")
    void fileCookiePersisterConstructorsAndNullChecks(@TempDir Path tempDir) {
        Path file = tempDir.resolve("cookies-iso.txt");
        FileCookiePersister persister = new FileCookiePersister(file, StandardCharsets.ISO_8859_1);
        assertEquals(file, persister.getPath());
        assertEquals(StandardCharsets.ISO_8859_1, persister.getCharset());

        assertThrows(NullPointerException.class, () -> new FileCookiePersister(null));
        assertThrows(NullPointerException.class, () -> new FileCookiePersister(file, null));
        assertThrows(NullPointerException.class, () -> FileCookiePersister.of((java.io.File) null));
        assertThrows(NullPointerException.class, () -> persister.saveAll(null));
    }

    @Test
    @DisplayName("FileCookiePersister factory and null validations")
    void fileCookiePersisterValidations() {
        assertThrows(NullPointerException.class, () -> new FileCookiePersister(null));
        assertThrows(NullPointerException.class, () -> new FileCookiePersister(Path.of("a"), null));
        assertThrows(NullPointerException.class, () -> FileCookiePersister.of((Path) null));
        assertThrows(NullPointerException.class, () -> FileCookiePersister.of((java.io.File) null));

        FileCookiePersister p = new FileCookiePersister(Path.of("test.cookies"), StandardCharsets.ISO_8859_1);
        assertEquals(StandardCharsets.ISO_8859_1, p.getCharset());
        assertThrows(NullPointerException.class, () -> p.saveAll(null));
    }

    @Test
    @DisplayName("FileCookiePersister of(File) factory creates persister with file path")
    void fileCookiePersisterOfFile(@TempDir Path tempDir) {
        java.io.File fileObj = tempDir.resolve("test-obj.cookies").toFile();
        FileCookiePersister persister = FileCookiePersister.of(fileObj);
        assertEquals(fileObj.toPath(), persister.getPath());
        assertEquals(StandardCharsets.UTF_8, persister.getCharset());
    }

    @Test
    @DisplayName("PersistentCookieStore in AUTO mode persists mutations immediately")
    void persistentCookieStoreAutoMode() {
        TestCookiePersister persister = new TestCookiePersister();
        PersistentCookieStore store = PersistentCookieStore.of(persister);

        assertEquals(PersistentCookieStore.PersistenceMode.AUTO, store.getPersistenceMode());
        assertSame(persister, store.getPersister());
        assertNotNull(store.getDelegate());
        assertEquals(0, store.size());

        HttpCookie c1 = new HttpCookie("sid", "101");
        c1.setDomain("app.com");
        c1.setPath("/");

        store.add(URI.create("https://app.com/"), c1);
        assertEquals(1, store.size());
        assertEquals(1, persister.saveCount.get());
        assertEquals(1, persister.lastSaved.size());
        assertEquals("sid", persister.lastSaved.get(0).getName());

        HttpCookie c2 = new HttpCookie("pref", "compact");
        c2.setDomain("app.com");
        c2.setPath("/");
        store.add(URI.create("https://app.com/"), c2);
        assertEquals(2, store.size());
        assertEquals(2, persister.saveCount.get());
        assertEquals(2, persister.lastSaved.size());

        store.remove(URI.create("https://app.com/"), c1);
        assertEquals(1, store.size());
        assertEquals(3, persister.saveCount.get());
        assertEquals(1, persister.lastSaved.size());
        assertEquals("pref", persister.lastSaved.get(0).getName());

        store.removeAll();
        assertEquals(0, store.size());
        assertTrue(persister.cleared.get());
    }

    @Test
    @DisplayName("PersistentCookieStore in MANUAL mode persists only on persist/sync call")
    void persistentCookieStoreManualMode() {
        TestCookiePersister persister = new TestCookiePersister();
        PersistentCookieStore store = PersistentCookieStore.of(persister, PersistentCookieStore.PersistenceMode.MANUAL);

        assertEquals(PersistentCookieStore.PersistenceMode.MANUAL, store.getPersistenceMode());

        HttpCookie c = new HttpCookie("token", "val");
        c.setDomain("api.io");
        c.setPath("/");

        store.add(URI.create("https://api.io/"), c);
        assertEquals(1, store.size());
        assertEquals(0, persister.saveCount.get()); // Not saved yet

        store.persist();
        assertEquals(1, persister.saveCount.get());
        assertEquals(1, persister.lastSaved.size());

        store.remove(URI.create("https://api.io/"), c);
        assertEquals(0, store.size());
        assertEquals(1, persister.saveCount.get()); // Still 1 until sync

        store.sync();
        assertEquals(2, persister.saveCount.get());
        assertEquals(0, persister.lastSaved.size());
    }

    @Test
    @DisplayName("PersistentCookieStore pre-loads cookies on creation and reloads on demand")
    void persistentCookieStorePreloadingAndReloading() {
        TestCookiePersister persister = new TestCookiePersister();
        HttpCookie preloaded = new HttpCookie("user", "alice");
        preloaded.setDomain("auth.com");
        preloaded.setPath("/profile");
        persister.preloaded.add(preloaded);

        PersistentCookieStore store = PersistentCookieStore.of(persister);
        assertEquals(1, store.size());
        assertEquals("alice", store.getCookies().get(0).getValue());

        List<HttpCookie> forUri = store.get(URI.create("https://auth.com/profile"));
        assertEquals(1, forUri.size());
        assertEquals("alice", forUri.get(0).getValue());

        // Update persister state externally and reload
        HttpCookie updated = new HttpCookie("user", "bob");
        updated.setDomain("auth.com");
        updated.setPath("/profile");
        persister.preloaded.clear();
        persister.preloaded.add(updated);

        store.reload();
        assertEquals(1, store.size());
        assertEquals("bob", store.getCookies().get(0).getValue());
    }

    @Test
    @DisplayName("PersistentCookieStore builder configuration and validations")
    void persistentCookieStoreBuilder() {
        TestCookiePersister persister = new TestCookiePersister();
        assertThrows(NullPointerException.class, () -> PersistentCookieStore.builder().build());

        PersistentCookieStore store = PersistentCookieStore.builder()
                .persister(persister)
                .mode(PersistentCookieStore.PersistenceMode.MANUAL)
                .build();

        assertSame(persister, store.getPersister());
        assertEquals(PersistentCookieStore.PersistenceMode.MANUAL, store.getPersistenceMode());
        assertNotNull(store.getDelegate());

        assertThrows(NullPointerException.class, () -> PersistentCookieStore.builder().delegate(null));
        assertThrows(NullPointerException.class, () -> PersistentCookieStore.builder().persister(null));
        assertThrows(NullPointerException.class, () -> PersistentCookieStore.builder().mode(null));
    }

    @Test
    @DisplayName("PersistentCookieStore thread-safety under concurrent access")
    void persistentCookieStoreConcurrentAccess() throws InterruptedException {
        TestCookiePersister persister = new TestCookiePersister();
        PersistentCookieStore store = PersistentCookieStore.of(persister);

        int threads = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        HttpCookie c = new HttpCookie("t" + threadId + "_c" + j, "v" + j);
                        c.setDomain("concurrency.test");
                        c.setPath("/");
                        store.add(URI.create("https://concurrency.test/"), c);
                        store.get(URI.create("https://concurrency.test/"));
                    }
                } catch (Exception e) {
                    fail("Concurrent operation failed: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threads * operationsPerThread, store.size());
        assertTrue(persister.saveCount.get() > 0);
    }

    @Test
    @DisplayName("SimpleModuleInfo and JRestlyClient builders configure CookieStore and file persistence")
    void buildersConfigureCookieStore(@TempDir Path tempDir) {
        Path cookieFile = tempDir.resolve("cookies.txt");
        PersistentCookieStore persistentStore = PersistentCookieStore.ofFile(cookieFile);

        var moduleInfo = SimpleModuleInfo.builder()
                .baseUrl("http://example.com")
                .cookieStore(persistentStore)
                .build();

        assertNotNull(moduleInfo.getCookieHandler());

        JRestlyClient client = JRestlyClient.builder()
                .baseUrl("http://example.com")
                .cookieStore(persistentStore, CookiePolicy.ACCEPT_ALL)
                .build();

        assertTrue(client.getCookieStore().isPresent());
        assertSame(persistentStore, client.getCookieStore().get());
        client.close();

        // One-line shortcut persistentCookieStore(Path)
        JRestlyClient fileClient = JRestlyClient.builder()
                .baseUrl("http://example.com")
                .persistentCookieStore(cookieFile)
                .build();

        assertTrue(fileClient.getCookieStore().isPresent());
        assertTrue(fileClient.getCookieStore().get() instanceof PersistentCookieStore);
        fileClient.close();
    }

    @Test
    @DisplayName("SimpleModuleInfo and JRestlyClient builders reject null CookieStore and Path")
    void builderNullValidations() {
        assertThrows(NullPointerException.class, () -> SimpleModuleInfo.builder().cookieStore(null));
        assertThrows(NullPointerException.class, () -> SimpleModuleInfo.builder().cookieStore(null, CookiePolicy.ACCEPT_ALL));
        assertThrows(NullPointerException.class, () -> SimpleModuleInfo.builder().cookieStore(new java.net.CookieManager().getCookieStore(), null));
        assertThrows(NullPointerException.class, () -> SimpleModuleInfo.builder().persistentCookieStore(null));

        assertThrows(NullPointerException.class, () -> JRestlyClient.builder().cookieStore(null));
        assertThrows(NullPointerException.class, () -> JRestlyClient.builder().cookieStore(null, CookiePolicy.ACCEPT_ALL));
        assertThrows(NullPointerException.class, () -> JRestlyClient.builder().cookieStore(new java.net.CookieManager().getCookieStore(), null));
        assertThrows(NullPointerException.class, () -> JRestlyClient.builder().persistentCookieStore(null));
    }

    @Test
    @DisplayName("PersistentCookieStore getURIs() delegates and builder supports custom delegate")
    void persistentCookieStoreGetUrisAndCustomDelegate() {
        TestCookiePersister persister = new TestCookiePersister();
        CookieStore customDelegate = new java.net.CookieManager().getCookieStore();

        PersistentCookieStore store = PersistentCookieStore.builder()
                .delegate(customDelegate)
                .persister(persister)
                .build();

        assertSame(customDelegate, store.getDelegate());
        assertTrue(store.getURIs().isEmpty());

        URI uri = URI.create("https://example.com/api");
        HttpCookie cookie = new HttpCookie("test", "val");
        cookie.setDomain("example.com");
        cookie.setPath("/api");
        store.add(uri, cookie);

        List<URI> uris = store.getURIs();
        assertFalse(uris.isEmpty());
        assertEquals(customDelegate.getURIs(), uris);
    }

    @Test
    @DisplayName("PersistentCookieStore handles .local domains, empty domains, and secure/path variations during preload")
    void persistentCookieStoreLocalDomainAndEmptyDomain() {
        TestCookiePersister persister = new TestCookiePersister();

        // 1. Cookie with .local domain (starts with dot)
        HttpCookie localCookie1 = new HttpCookie("c1", "v1");
        localCookie1.setDomain(".myservice.local");
        localCookie1.setPath("/app");
        localCookie1.setSecure(true);

        // 2. Cookie with .local domain (no leading dot)
        HttpCookie localCookie2 = new HttpCookie("c2", "v2");
        localCookie2.setDomain("backend.local");
        localCookie2.setPath("");
        localCookie2.setSecure(false);

        // 2b. Cookie with .local domain and null path
        HttpCookie localCookieNullPath = new HttpCookie("cNullPath", "vNull");
        localCookieNullPath.setDomain("nullpath.local");
        localCookieNullPath.setPath(null);
        localCookieNullPath.setSecure(true);

        // 3. Cookie with null domain and blank path
        HttpCookie noDomainCookie = new HttpCookie("c3", "v3");
        noDomainCookie.setDomain(null);
        noDomainCookie.setPath(null);

        // 4. Cookie with empty domain
        HttpCookie emptyDomainCookie = new HttpCookie("c4", "v4");
        emptyDomainCookie.setDomain("   ");

        // 5. Expired cookie should be skipped
        HttpCookie expiredCookie = new HttpCookie("c5", "v5");
        expiredCookie.setDomain("expired.com");
        expiredCookie.setMaxAge(0);

        persister.preloaded.addAll(List.of(localCookie1, localCookie2, localCookieNullPath, noDomainCookie, emptyDomainCookie, expiredCookie));

        PersistentCookieStore store = PersistentCookieStore.of(persister);
        assertEquals(5, store.size());

        // Verifying cookies for bare host (without .local)
        List<HttpCookie> forBareHost1 = store.get(URI.create("https://myservice/app"));
        assertEquals(1, forBareHost1.size());
        assertEquals("c1", forBareHost1.get(0).getName());

        List<HttpCookie> forBareHost2 = store.get(URI.create("http://backend/"));
        assertEquals(1, forBareHost2.size());
        assertEquals("c2", forBareHost2.get(0).getName());
    }

    @Test
    @DisplayName("FileCookiePersister handles path without parent directory")
    void fileCookiePersisterWithoutParentPath() throws IOException {
        Path directFile = Path.of("direct-cookies-" + System.nanoTime() + ".txt");
        assertNull(directFile.getParent(), "Path without parent must have null getParent()");
        try {
            FileCookiePersister persister = FileCookiePersister.of(directFile);
            HttpCookie c = new HttpCookie("k", "v");
            c.setDomain("test.local");
            persister.saveAll(List.of(c));

            assertTrue(Files.exists(directFile));
            assertEquals(1, persister.loadAll().size());
            persister.clear();
            assertFalse(Files.exists(directFile));
        } finally {
            Files.deleteIfExists(directFile);
        }
    }

    @Test
    @DisplayName("CookieCodec write handles null and expired cookies in collection")
    void cookieCodecWriteWithNullsAndExpired() throws IOException {
        StringWriter writer = new StringWriter();
        HttpCookie valid = new HttpCookie("valid", "ok");
        HttpCookie expired = new HttpCookie("expired", "old");
        expired.setMaxAge(0);

        List<HttpCookie> mixed = new ArrayList<>();
        mixed.add(null);
        mixed.add(expired);
        mixed.add(valid);

        CookieCodec.write(writer, mixed);
        String output = writer.toString();
        assertTrue(output.contains("valid=ok"));
        assertFalse(output.contains("expired=old"));
    }

    @Test
    @DisplayName("PersistentCookieStore handles null persister load, expired cookies in delegate and auto-remove missed")
    void persistentCookieStoreEdgeCasesAndNullSafety() {
        // 1. Persister returning null from loadAll()
        CookiePersister nullPersister = new CookiePersister() {
            @Override public List<HttpCookie> loadAll() { return null; }
            @Override public void saveAll(java.util.Collection<HttpCookie> cookies) {}
            @Override public void clear() {}
        };
        PersistentCookieStore storeWithNullPersister = PersistentCookieStore.of(nullPersister);
        assertEquals(0, storeWithNullPersister.size());

        // 2. Persister returning list with null and domain without bareHost (e.g. ".local")
        TestCookiePersister persister = new TestCookiePersister();
        HttpCookie bareLocal = new HttpCookie("bl", "v");
        bareLocal.setDomain(".local"); // bareHost becomes empty string ""
        bareLocal.setPath(null); // secure path fallback
        bareLocal.setSecure(true);

        List<HttpCookie> loadedList = new ArrayList<>();
        loadedList.add(null);
        loadedList.add(bareLocal);
        persister.preloaded.addAll(loadedList);

        PersistentCookieStore storeWithBareLocal = PersistentCookieStore.of(persister);
        assertEquals(1, storeWithBareLocal.size());

        // 3. remove in AUTO mode when cookie does not exist in store (removed == false)
        HttpCookie nonExistent = new HttpCookie("ghost", "boo");
        nonExistent.setDomain("app.com");
        int saveCountBefore = persister.saveCount.get();
        boolean removed = storeWithBareLocal.remove(URI.create("https://app.com/"), nonExistent);
        assertFalse(removed);
        assertEquals(saveCountBefore, persister.saveCount.get(), "syncInternal must not be called when remove returns false");

        // 4. syncInternal and getCookies filter out expired cookies from delegate
        persister.preloaded.clear();
        CookieStore stubDelegate = new CookieStore() {
            @Override public void add(URI uri, HttpCookie cookie) {}
            @Override public List<HttpCookie> get(URI uri) { return List.of(); }
            @Override public List<HttpCookie> getCookies() {
                HttpCookie expired = new HttpCookie("expired", "1");
                expired.setMaxAge(0);
                HttpCookie active = new HttpCookie("active", "2");
                active.setMaxAge(3600);
                return List.of(expired, active);
            }
            @Override public List<URI> getURIs() { return List.of(); }
            @Override public boolean remove(URI uri, HttpCookie cookie) { return false; }
            @Override public boolean removeAll() { return false; }
        };

        PersistentCookieStore storeWithExpiredInDelegate = PersistentCookieStore.builder()
                .delegate(stubDelegate)
                .persister(persister)
                .build();
        // getCookies() must filter out expired cookie and retain active
        assertEquals(1, storeWithExpiredInDelegate.getCookies().size());
        assertEquals("active", storeWithExpiredInDelegate.getCookies().get(0).getName());
        // sync() must save only non-expired cookies
        storeWithExpiredInDelegate.sync();
        assertEquals(1, persister.lastSaved.size());
        assertEquals("active", persister.lastSaved.get(0).getName());
    }

    @Test
    @DisplayName("FileCookiePersister wraps IOExceptions into UncheckedIOException on load, save, and clear")
    void fileCookiePersisterIOExceptionHandling(@TempDir Path tempDir) throws IOException {
        Path dir = tempDir.resolve("dir-target");
        Files.createDirectories(dir);
        FileCookiePersister persister = FileCookiePersister.of(dir);

        // Reading directory as a file throws UncheckedIOException
        assertThrows(java.io.UncheckedIOException.class, persister::loadAll);

        // Non-empty directory cannot be deleted with deleteIfExists -> throws UncheckedIOException
        Files.createFile(dir.resolve("child.txt"));
        assertThrows(java.io.UncheckedIOException.class, persister::clear);

        // Moving temp file over non-empty directory throws UncheckedIOException
        assertThrows(java.io.UncheckedIOException.class, () -> persister.saveAll(List.of(new HttpCookie("sid", "123"))));
    }


    private static final class TestCookiePersister implements CookiePersister {
        final List<HttpCookie> preloaded = new ArrayList<>();
        final List<HttpCookie> lastSaved = new ArrayList<>();
        final AtomicInteger saveCount = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean cleared = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public List<HttpCookie> loadAll() {
            return new ArrayList<>(preloaded);
        }

        @Override
        public void saveAll(java.util.Collection<HttpCookie> cookies) {
            saveCount.incrementAndGet();
            lastSaved.clear();
            lastSaved.addAll(cookies);
        }

        @Override
        public void clear() {
            cleared.set(true);
            lastSaved.clear();
            preloaded.clear();
        }
    }
}
