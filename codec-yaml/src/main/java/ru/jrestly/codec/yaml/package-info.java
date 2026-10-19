/**
 * YAML payload codec for jRestly (ADR-0073).
 *
 * <p>Binds {@code application/yaml} / {@code text/yaml} / {@code *+yaml}
 * payloads through the core {@link ru.jrestly.codec.PayloadCodec} SPI, backed
 * by a Jackson {@code YAMLMapper}. The codec lives outside the zero-dependency
 * core: register it explicitly with
 * {@code JRestlyClient.builder().codec(...)} and the declarative layer keeps
 * working unchanged for either JSON or YAML.
 */
package ru.jrestly.codec.yaml;
