package ru.jrestly.cookie;

import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A thread-safe, persistent implementation of {@link CookieStore} (ADR-0039).
 * <p>
 * Delegates in-memory cookie storage to an underlying {@link CookieStore} while persisting active,
 * non-expired cookies through a pluggable {@link CookiePersister}.
 */
public final class PersistentCookieStore implements CookieStore {

    /**
     * Policy determining when in-memory cookie mutations are persisted.
     */
    public enum PersistenceMode {
        /**
         * Automatically persists cookies immediately upon any mutation ({@code add}, {@code remove}, {@code removeAll}).
         */
        AUTO,

        /**
         * Persists cookies only when explicitly requested via {@link #persist()} or {@link #sync()}.
         */
        MANUAL
    }

    private final CookieStore delegate;
    private final CookiePersister persister;
    private final PersistenceMode persistenceMode;
    private final Object lock = new Object();

    /**
     * Constructs a persistent cookie store with the specified persister and default settings (AUTO mode, default delegate).
     *
     * @param persister the persistence SPI implementation, not {@code null}
     */
    public PersistentCookieStore(CookiePersister persister) {
        this(new CookieManager().getCookieStore(), persister, PersistenceMode.AUTO);
    }

    /**
     * Constructs a persistent cookie store with custom delegate, persister, and persistence mode.
     *
     * @param delegate underlying in-memory store, not {@code null}
     * @param persister persistence SPI implementation, not {@code null}
     * @param persistenceMode persistence mode, not {@code null}
     */
    public PersistentCookieStore(CookieStore delegate, CookiePersister persister, PersistenceMode persistenceMode) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.persister = Objects.requireNonNull(persister, "persister must not be null");
        this.persistenceMode = Objects.requireNonNull(persistenceMode, "persistenceMode must not be null");
        loadInternal();
    }

    /**
     * Creates a persistent cookie store backed by the specified persister in AUTO mode.
     *
     * @param persister the cookie persister, not {@code null}
     * @return a new persistent cookie store
     */
    public static PersistentCookieStore of(CookiePersister persister) {
        return new PersistentCookieStore(persister);
    }

    /**
     * Creates a persistent cookie store backed by the specified persister in the given mode.
     *
     * @param persister the cookie persister, not {@code null}
     * @param mode persistence mode, not {@code null}
     * @return a new persistent cookie store
     */
    public static PersistentCookieStore of(CookiePersister persister, PersistenceMode mode) {
        return new PersistentCookieStore(new CookieManager().getCookieStore(), persister, mode);
    }

    /**
     * Creates a persistent cookie store backed by a local file in AUTO mode.
     *
     * @param path target file path, not {@code null}
     * @return a new persistent cookie store
     */
    public static PersistentCookieStore ofFile(Path path) {
        return ofFile(path, PersistenceMode.AUTO);
    }

    /**
     * Creates a persistent cookie store backed by a local file in the given mode.
     *
     * @param path target file path, not {@code null}
     * @param mode persistence mode, not {@code null}
     * @return a new persistent cookie store
     */
    public static PersistentCookieStore ofFile(Path path, PersistenceMode mode) {
        return new PersistentCookieStore(new CookieManager().getCookieStore(), FileCookiePersister.of(path), mode);
    }

    /**
     * Returns a new builder for configuring a {@link PersistentCookieStore}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the configured persistence SPI implementation.
     *
     * @return the persister
     */
    public CookiePersister getPersister() {
        return persister;
    }

    /**
     * Returns the active persistence mode.
     *
     * @return the persistence mode
     */
    public PersistenceMode getPersistenceMode() {
        return persistenceMode;
    }

    /**
     * Returns the underlying in-memory delegate cookie store.
     *
     * @return the delegate cookie store
     */
    public CookieStore getDelegate() {
        return delegate;
    }

    /**
     * Returns the number of currently active, non-expired cookies in this store.
     *
     * @return count of non-expired cookies
     */
    public int size() {
        return getCookies().size();
    }

    /**
     * Manually triggers persistence of all currently active, non-expired cookies.
     */
    public void persist() {
        sync();
    }

    /**
     * Synchronizes in-memory cookies with the persistent store.
     */
    public void sync() {
        synchronized (lock) {
            syncInternal();
        }
    }

    /**
     * Reloads cookies from the persistent store into the delegate, replacing current state.
     */
    public void reload() {
        synchronized (lock) {
            delegate.removeAll();
            loadInternal();
        }
    }

    private void loadInternal() {
        List<HttpCookie> loaded = persister.loadAll();
        if (loaded != null) {
            for (HttpCookie cookie : loaded) {
                if (cookie != null && !cookie.hasExpired()) {
                    URI uri = resolveUri(cookie);
                    delegate.add(uri, cookie);
                    if (cookie.getDomain() != null && cookie.getDomain().endsWith(".local")) {
                        String clean = cookie.getDomain().startsWith(".") ? cookie.getDomain().substring(1) : cookie.getDomain();
                        if (clean.endsWith(".local")) {
                            String bareHost = clean.substring(0, clean.length() - ".local".length());
                            String scheme = cookie.getSecure() ? "https://" : "http://";
                            String path = cookie.getPath() != null && !cookie.getPath().isBlank() ? cookie.getPath() : "/";
                            try {
                                delegate.add(URI.create(scheme + bareHost + path), cookie);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
            }
        }
    }

    private void syncInternal() {
        List<HttpCookie> active = delegate.getCookies();
        List<HttpCookie> valid = new ArrayList<>(active.size());
        for (HttpCookie cookie : active) {
            if (!cookie.hasExpired()) {
                valid.add(cookie);
            }
        }
        persister.saveAll(valid);
    }

    private static URI resolveUri(HttpCookie cookie) {
        String domain = cookie.getDomain();
        if (domain == null || domain.isBlank()) {
            return null;
        }
        String cleanDomain = domain.startsWith(".") ? domain.substring(1) : domain;
        String scheme = cookie.getSecure() ? "https://" : "http://";
        String path = cookie.getPath() != null && !cookie.getPath().isBlank() ? cookie.getPath() : "/";
        try {
            return URI.create(scheme + cleanDomain + path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void add(URI uri, HttpCookie cookie) {
        Objects.requireNonNull(cookie, "cookie must not be null");
        synchronized (lock) {
            delegate.add(uri, cookie);
            if (persistenceMode == PersistenceMode.AUTO) {
                syncInternal();
            }
        }
    }

    @Override
    public List<HttpCookie> get(URI uri) {
        synchronized (lock) {
            return delegate.get(uri);
        }
    }

    @Override
    public List<HttpCookie> getCookies() {
        synchronized (lock) {
            List<HttpCookie> all = delegate.getCookies();
            List<HttpCookie> valid = new ArrayList<>(all.size());
            for (HttpCookie c : all) {
                if (!c.hasExpired()) {
                    valid.add(c);
                }
            }
            return Collections.unmodifiableList(valid);
        }
    }

    @Override
    public List<URI> getURIs() {
        synchronized (lock) {
            return delegate.getURIs();
        }
    }

    @Override
    public boolean remove(URI uri, HttpCookie cookie) {
        synchronized (lock) {
            boolean removed = delegate.remove(uri, cookie);
            if (removed && persistenceMode == PersistenceMode.AUTO) {
                syncInternal();
            }
            return removed;
        }
    }

    @Override
    public boolean removeAll() {
        synchronized (lock) {
            boolean result = delegate.removeAll();
            persister.clear();
            return result;
        }
    }

    /**
     * Builder for configuring and constructing {@link PersistentCookieStore} instances.
     */
    public static final class Builder {
        private CookieStore delegate;
        private CookiePersister persister;
        private PersistenceMode persistenceMode = PersistenceMode.AUTO;

        private Builder() {
        }

        /**
         * Sets the underlying delegate {@link CookieStore}.
         *
         * @param delegate in-memory store delegate, not {@code null}
         * @return this builder
         */
        public Builder delegate(CookieStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            return this;
        }

        /**
         * Sets the {@link CookiePersister} implementation.
         *
         * @param persister the persistence SPI implementation, not {@code null}
         * @return this builder
         */
        public Builder persister(CookiePersister persister) {
            this.persister = Objects.requireNonNull(persister, "persister must not be null");
            return this;
        }

        /**
         * Sets the persistence mode ({@link PersistenceMode#AUTO} or {@link PersistenceMode#MANUAL}).
         *
         * @param mode the persistence mode, not {@code null}
         * @return this builder
         */
        public Builder mode(PersistenceMode mode) {
            this.persistenceMode = Objects.requireNonNull(mode, "mode must not be null");
            return this;
        }

        /**
         * Builds and returns a new {@link PersistentCookieStore}.
         *
         * @return a new persistent cookie store
         */
        public PersistentCookieStore build() {
            CookieStore effectiveDelegate = delegate != null ? delegate : new CookieManager().getCookieStore();
            CookiePersister effectivePersister = Objects.requireNonNull(persister, "persister must be configured");
            return new PersistentCookieStore(effectiveDelegate, effectivePersister, persistenceMode);
        }
    }
}
