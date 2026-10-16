/**
 * Apache HttpClient 5 transport engine for jRestly (ADR-0058, phase 2).
 *
 * <p>Published as the optional {@code jrestly-engine-apache5} submodule: when it is on the
 * classpath, {@link java.util.ServiceLoader} discovery picks the Apache5 transport for every
 * engine-less client build; the zero-dependency {@code JdkHttpEngine} remains the fallback.
 * The module owns its transport dependency ({@code httpclient5}), while the core stays
 * dependency-free.
 */
package ru.jrestly.engine.apache5;
