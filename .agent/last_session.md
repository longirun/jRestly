# Last Session: #339 (2026-10-20)

## Что сделано

**Сессия #339: старт цикла 0.13.0 — бенчмарк-матрица полиглотных кодеков (`CodecBenchmark`: JSON/XML/YAML/Protobuf × decode/encode + registry resolve), пилотные замеры, JSON-only пиннинг реестра в baseline-клиентах, секция в `BENCHMARKS.md`; гейт 1,835/1,835.**

1. *Гигиена базлайнов:* `benchmarks/build.gradle` получил `implementation project(':codec-jackson-xml'/'codec-yaml'/'codec-protobuf')`. Чтобы ServiceLoader-провайдеры не расширяли реестр в дефолтных клиентах и не сдвигали исторические JSON-цифры, `JRestlyBenchmarkClient` (оба конструктора) и `DispatchOverheadBenchmark` пиннят `CodecRegistry.of(JsonCodecResolver.resolve())` — тот же принцип, что пиннинг JDK-движка (ADR-0058).
2. *`CodecBenchmark` (9 arm'ов):* decode/encode для JSON (`JsonCodecAdapter(JacksonCodec.defaults())`), XML (`JacksonXmlCodec`), YAML (`YamlCodec`), Protobuf (`ProtobufCodec`), плюс `registry_resolveJson` на 4-codec registry с media-type `Application/JSON; charset=UTF-8`. Payload'ы намеренно разные (UserDto-документ vs schema-bound `Timestamp`): матрица измеряет стоимость SPI на вызов, не рейтинг форматов.
3. *Пилот (JMH, `-prof gc`, `-f 1 -wi 1 -i 2`; YAML пере-проверен `-wi 2 -i 3`):*
   - JSON: decode ~1.2 µs / 1,256 B/op; encode ~840 ns / 632 B;
   - XML: decode ~6.4 µs / 3,896 B; encode ~2.3 µs / 1,464 B;
   - YAML: decode **27–60 µs / 22.7 KB** (outlier, разрыв подтверждён), encode ~9.4–10.2 µs / 5.2 KB;
   - Protobuf: decode ~55 ns / 56 B; encode ~29 ns / 32 B;
   - registry resolve: ~136 ns / 112 B.
4. *Документация:* `BENCHMARKS.md` — новая секция «Polyglot codec overhead (cycle 0.13.0, run #339)» с таблицей, пятью инсайтами, командой воспроизведения и codec fairness note.
5. *TDD/гейт:* `CodecBenchmarkTest` (2 теста) — все 8 arm'ов исполняются и биндят свой payload, wire-size-порядок protobuf < YAML < JSON < XML. Полный **1,835/1,835** (root 1,671 + engine-okhttp 25 + engine-apache5 25 + engine-netty 26 + codec-jackson-xml 15 + codec-yaml 15 + codec-protobuf 19 + **benchmarks 39**). Main-код ядра не тронут, javadoc-гейт не затронут (benchmarks javadoc disabled).

## Контекст для следующей сессии (#340)

1. **Ветка** `agent-loop`, HEAD — коммит #339.
2. **Highest ADR:** **0079**, next free **0080** (новых ADR в #339 не было).
3. **Цикл 0.12.0 закрыт; цикл 0.13.0 открыт, первый шаг сделан.**
4. **Пул 0.13.0, приоритет:**
   - (a) **Аудит `YamlCodec.deserialize`** — подтверждённый outlier матрицы (27–60 µs, 22.7 KB/op против JSON 1.2 µs): SnakeYAML event pipeline. Гипотезы для проверки: `mapper.getTypeFactory().constructType(type)` на каждый вызов (кэш по `Type`?), переиспользование parser'а, `YAMLFactory` feature-настройки. TDD + JMH-подтверждение. Оформить ADR-0080, если найдётся реальное улучшение.
   - (b) независимый аудит кэшей/рефлексии `MethodMetadata`/`ControllerValidator`;
   - (c) HTTP/3 / QUIC или client-side load balancing SPI;
   - (d) опционально — publishable-прогон `CodecBenchmark` (`-f 2 -wi 5 -i 10`) на тихой машине.
5. *Замечания по окружению:*
   - `GRADLE_USER_HOME=/tmp/opencode/gradle-home ./gradlew ...`;
   - быстрый JMH: `./gradlew :benchmarks:jmh -PjmhArgs="-f 1 -wi 1 -i 2 -tu ns -bm avgt -prof gc -rf json -rff /tmp/opencode/codec_results.json .*CodecBenchmark\..*"`;
   - подсчёт тестов: `awk -F'tests="' 'NF>1 {split($2,a,"\""); sum+=a[1]} END {print "Total tests:", sum}' build/test-results/test/*.xml */build/test-results/test/*.xml`;
   - JaCoCo: `build/reports/jacoco/test/jacocoTestReport.xml`.
6. Метрики: гейт **1,835/1,835** (root 1,671 + субмодули 164). JaCoCo держится на рекорде #338 (Branch 90.81–90.85%), в #339 не пере-мерялся (код ядра не тронут).
