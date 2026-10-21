package dev.jeefox.smartmeal.price.refresh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetailSnapshotImporterTest {
    @TempDir Path directory;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private RetailSnapshotImporter importer() {
        return new RetailSnapshotImporter(jdbc, transaction, mapper,
                new CatalogueProductMatcher(), directory.toString());
    }

    private RetailSnapshotImporter.Snapshot snapshot(String price, String url, boolean complete, Instant date) {
        return new RetailSnapshotImporter.Snapshot(1, "perekrestok", complete, date.toString(),
                "SELECTED_CATEGORIES", new RetailSnapshotImporter.Context(null, null, "Москва", "81",
                "SITE_DEFAULT_REGION"), List.of(new RetailSnapshotImporter.Category("100")), List.of(
                new RetailSnapshotImporter.Item("12", "Гречка ядрица 900г", url, price, "RUB", false,
                        "900 г", "Крупы и макароны", true)));
    }

    @Test
    void rejectsPartialStaleForeignAndInvalidPriceSnapshots() {
        var importer = importer();
        String url = "https://www.perekrestok.ru/cat/101/p/grechka-12";
        assertThrows(IllegalArgumentException.class, () -> importer.validate(snapshot("99.00", url, false, Instant.now()), "perekrestok"));
        assertThrows(IllegalArgumentException.class, () -> importer.validate(snapshot("99.00", url, true,
                Instant.now().minus(16, ChronoUnit.DAYS)), "perekrestok"));
        assertThrows(IllegalArgumentException.class, () -> importer.validate(snapshot("99.00", "https://evil.test/a", true, Instant.now()), "perekrestok"));
        assertThrows(IllegalArgumentException.class, () -> importer.validate(snapshot("-1", url, true, Instant.now()), "perekrestok"));
        assertThrows(IllegalArgumentException.class, () -> importer.validate(snapshot("99.00", url, true, Instant.now()), "magnit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsOrdinaryPriceAndPreservesOriginalRetrievalTime() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                new RetailSnapshotImporter.Ingredient(5, "Гречневая крупа")));
        doAnswer(call -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action = call.getArgument(0);
            action.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        Instant date = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        String url = "https://www.perekrestok.ru/cat/101/p/grechka-12";
        Files.createDirectories(directory.resolve("perekrestok"));
        // Extra display fields in a collector snapshot must not change which price is imported.
        String json = mapper.writeValueAsString(snapshot("143.99", url, true, date))
                .replace("\"regularPrice\":\"143.99\"", "\"regularPrice\":\"143.99\",\"currentPrice\":\"109.99\"");
        Files.writeString(directory.resolve("perekrestok/latest.json"), json);
        importer().importLatest();
        verify(jdbc).update(contains("insert into catalogue_price_references"), eq(5L), eq("Гречка ядрица 900г"),
                eq(900), eq(new BigDecimal("143.99")), eq("Перекрёсток · 81"), eq(url),
                contains("Москва"), eq(java.sql.Timestamp.from(date)),
                eq(java.sql.Timestamp.from(date.plus(15, ChronoUnit.DAYS))), eq(false));
    }

    @Test
    void malformedSnapshotNeverStartsTransaction() throws Exception {
        Files.createDirectories(directory.resolve("perekrestok"));
        Files.writeString(directory.resolve("perekrestok/latest.json"), "{not json}");
        importer().importLatest();
        verifyNoInteractions(transaction, jdbc);
    }

    @Test
    void pageObservationsRequireAnExplicitPageNumber() {
        var base = snapshot("99.00", "https://www.perekrestok.ru/cat/101/p/grechka-12", true, Instant.now());
        var page = new RetailSnapshotImporter.Snapshot(1, base.chain(), true, base.retrievedAt(),
                "OBSERVED_PAGE", base.sourceContext(), List.of(new RetailSnapshotImporter.Category("100", 1)), base.products());
        assertDoesNotThrow(() -> importer().validate(page, "perekrestok"));
        var invalid = new RetailSnapshotImporter.Snapshot(1, base.chain(), true, base.retrievedAt(),
                "OBSERVED_PAGE", base.sourceContext(), base.categories(), base.products());
        assertThrows(IllegalArgumentException.class, () -> importer().validate(invalid, "perekrestok"));
    }
}
