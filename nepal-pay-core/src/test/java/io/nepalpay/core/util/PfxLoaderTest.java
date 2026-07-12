package io.nepalpay.core.util;

import io.nepalpay.core.exception.ConnectIpsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PfxLoader}.
 *
 * <p>Pure unit test — no Spring context, no mocking.
 * Uses JUnit 5 {@code @TempDir} for real temporary files.
 */
@DisplayName("PfxLoader")
class PfxLoaderTest {

    @TempDir
    Path tempDir;

    // ── validatePath() ────────────────────────────────────────────────────

    @Test
    @DisplayName("validatePath: valid path passes without exception")
    void validatePath_validPath_noException() {
        PfxLoader.validatePath("file:/app/CREDITOR.pfx");
        // no exception = pass
    }

    @Test
    @DisplayName("validatePath: null throws ConnectIpsException")
    void validatePath_null_throws() {
        assertThatThrownBy(() -> PfxLoader.validatePath(null))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("pfx-path is not configured");
    }

    @Test
    @DisplayName("validatePath: blank throws ConnectIpsException")
    void validatePath_blank_throws() {
        assertThatThrownBy(() -> PfxLoader.validatePath("   "))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("pfx-path is not configured");
    }

    @Test
    @DisplayName("validatePath: empty string throws ConnectIpsException")
    void validatePath_emptyString_throws() {
        assertThatThrownBy(() -> PfxLoader.validatePath(""))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("pfx-path is not configured");
    }

    // ── read() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("read: valid InputStream returns bytes correctly")
    void read_validInputStream_returnsBytesCorrectly() {
        byte[] expected = {1, 2, 3, 4, 5};
        InputStream stream = new ByteArrayInputStream(expected);

        byte[] result = PfxLoader.read(stream, "file:/app/CREDITOR.pfx");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("read: closes InputStream after reading (try-with-resources)")
    void read_closesStream_afterReading() throws IOException {
        // Write a real file and open a real stream to verify it's closed
        Path pfxFile = tempDir.resolve("test.pfx");
        Files.write(pfxFile, new byte[]{10, 20, 30});

        InputStream stream = Files.newInputStream(pfxFile);
        byte[] result = PfxLoader.read(stream, "file:" + pfxFile);

        assertThat(result).isEqualTo(new byte[]{10, 20, 30});
        // If stream was closed, reading again throws — confirms try-with-resources
        assertThatThrownBy(stream::read)
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("read: null InputStream throws ConnectIpsException")
    void read_nullInputStream_throws() {
        assertThatThrownBy(() ->
                PfxLoader.read(null, "file:/app/CREDITOR.pfx"))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("InputStream is null");
    }

    @Test
    @DisplayName("read: empty InputStream throws ConnectIpsException")
    void read_emptyInputStream_throws() {
        InputStream empty = new ByteArrayInputStream(new byte[0]);

        assertThatThrownBy(() ->
                PfxLoader.read(empty, "file:/app/empty.pfx"))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("read: pfxPath used in error message for debugging")
    void read_pfxPathAppearsInErrorMessage() {
        InputStream empty = new ByteArrayInputStream(new byte[0]);

        assertThatThrownBy(() ->
                PfxLoader.read(empty, "file:/app/CREDITOR.pfx"))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("file:/app/CREDITOR.pfx");
    }
}