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
