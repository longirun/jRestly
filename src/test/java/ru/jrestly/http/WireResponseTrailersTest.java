package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireResponseTrailersTest {

    @Test
    @DisplayName("WireResponse backward compatible constructors and normalization for trailersSupplier")
    void constructorsAndNormalization() {
        // 3-arg
        WireResponse wr3 = new WireResponse(200, List.of(new Header("k", "v")), new byte[0]);
        assertNotNull(wr3.trailersSupplier());
        assertTrue(wr3.trailersSupplier().get().isEmpty());

        // 4-arg
        WireResponse wr4 = new WireResponse(200, List.of(), null, null);
        assertNotNull(wr4.trailersSupplier());
        assertTrue(wr4.trailersSupplier().get().isEmpty());

        // 5-arg
        WireResponse wr5 = new WireResponse(200, List.of(), null, null, HttpClient.Version.HTTP_2);
        assertNotNull(wr5.trailersSupplier());
        assertTrue(wr5.trailersSupplier().get().isEmpty());

        // 6-arg with static trailers
        List<Header> staticTrailers = List.of(new Header("grpc-status", "0"));
        WireResponse wr6 = new WireResponse(200, List.of(), new byte[0], null, HttpClient.Version.HTTP_2, staticTrailers);
        assertEquals(staticTrailers, wr6.trailers());
        assertNotNull(wr6.trailersSupplier());
        assertEquals(staticTrailers, wr6.trailersSupplier().get());

        // 6-arg with null trailers
        WireResponse wr6Null = new WireResponse(200, List.of(), null, null, null, null);
        assertNotNull(wr6Null.trailersSupplier());
        assertTrue(wr6Null.trailersSupplier().get().isEmpty());

        // 7-arg canonical constructor with explicit supplier
        Supplier<List<Header>> supplier = () -> List.of(new Header("x-trailer", "val"));
        WireResponse wr7 = new WireResponse(200, List.of(), null, null, HttpClient.Version.HTTP_1_1, List.of(), supplier);
        assertEquals(supplier, wr7.trailersSupplier());
        assertEquals("val", wr7.trailersSupplier().get().get(0).value());

        // 7-arg with null supplier defaults gracefully
        WireResponse wr7Null = new WireResponse(200, List.of(), null, null, HttpClient.Version.HTTP_1_1, List.of(), null);
        assertNotNull(wr7Null.trailersSupplier());
        assertTrue(wr7Null.trailersSupplier().get().isEmpty());
    }

    @Test
    @DisplayName("WireResponse equals and hashCode ignore trailersSupplier (transport hook, not value)")
    void equalsAndHashCodeIgnoreTrailersSupplier() {
        Supplier<List<Header>> supplier1 = () -> List.of(new Header("x-t", "1"));
        Supplier<List<Header>> supplier2 = () -> List.of(new Header("x-t", "2"));

        WireResponse wr1 = new WireResponse(200, List.of(), null, null, HttpClient.Version.HTTP_1_1, List.of(), supplier1);
        WireResponse wr2 = new WireResponse(200, List.of(), null, null, HttpClient.Version.HTTP_1_1, List.of(), supplier2);

        assertEquals(wr1, wr2, "Responses differing only by trailersSupplier must be equal per ADR-0059");
        assertEquals(wr1.hashCode(), wr2.hashCode(), "hashCode must match when responses are equal");

        // Changing a value component breaks equality
        WireResponse wrOtherStatus = new WireResponse(201, List.of(), null, null, HttpClient.Version.HTTP_1_1, List.of(), supplier1);
        assertNotEquals(wr1, wrOtherStatus);
    }

    @Test
    @DisplayName("WireResponse.Builder populates trailersSupplier")
    void builderTrailersSupplier() {
        Supplier<List<Header>> supplier = () -> List.of(new Header("x-built", "ok"));
        WireResponse response = WireResponse.builder()
                .statusCode(200)
                .trailersSupplier(supplier)
                .build();

        assertNotNull(response.trailersSupplier());
        assertEquals("ok", response.trailersSupplier().get().get(0).value());
    }
}
