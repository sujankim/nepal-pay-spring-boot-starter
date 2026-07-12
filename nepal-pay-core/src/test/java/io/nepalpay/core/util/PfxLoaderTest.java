package io.nepalpay.core.util;

import io.nepalpay.core.exception.ConnectIpsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PfxLoader}.
 *
 * <p>Uses JUnit 5 {@code @TempDir} to create real temporary files —
 * no mocking needed. Pure unit test — no Spring context.
 */
@DisplayName("PfxLoader")
class PfxLoaderTest {

    private final ResourceLoader resourceLoader =
            new DefaultResourceLoader();

    @TempDir
    Path tempDir;

    // ── Happy path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("loads file bytes correctly from a valid file: path")
    void load_validFile_returnsBytesCorrectly() throws IOException {
        byte[] expected = {1, 2, 3, 4, 5};
        Path pfxFile = tempDir.resolve("CREDITOR.pfx");
        Files.write(pfxFile, expected);

        byte[] result = PfxLoader.load(
                "file:" + pfxFile.toAbsolutePath(), resourceLoader);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("loads file bytes correctly from a classpath: path")
    void load_classpathResource_returnsBytesCorrectly() {
        // Use a known classpath resource — any test resource will do
        // We use the test class itself as a classpath resource marker
        // In practice any non-empty classpath file works
        byte[] result = PfxLoader.load(
                "classpath:io/nepalpay/core/util/PfxLoaderTest.class",
                resourceLoader);

        assertThat(result).isNotEmpty();
    }

    // ── Validation errors ─────────────────────────────────────────────────

    @Test
    @DisplayName("throws ConnectIpsException when pfxPath is null")
    void load_nullPath_throwsConnectIpsException() {
        assertThatThrownBy(() ->
                PfxLoader.load(null, resourceLoader))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("pfx-path is not configured");
    }

    @Test
    @DisplayName("throws ConnectIpsException when pfxPath is blank")
    void load_blankPath_throwsConnectIpsException() {
        assertThatThrownBy(() ->
                PfxLoader.load("   ", resourceLoader))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("pfx-path is not configured");
    }

    @Test
    @DisplayName("throws ConnectIpsException when file does not exist")
    void load_nonExistentFile_throwsConnectIpsException() {
        assertThatThrownBy(() ->
                PfxLoader.load(
                        "file:/nonexistent/path/CREDITOR.pfx",
                        resourceLoader))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("not found at path");
    }

    @Test
    @DisplayName("throws ConnectIpsException when file is empty")
    void load_emptyFile_throwsConnectIpsException() throws IOException {
        Path emptyPfx = tempDir.resolve("empty.pfx");
        Files.write(emptyPfx, new byte[0]);

        assertThatThrownBy(() ->
                PfxLoader.load(
                        "file:" + emptyPfx.toAbsolutePath(),
                        resourceLoader))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("throws ConnectIpsException when path is empty string")
    void load_emptyString_throwsConnectIpsException() {
        assertThatThrownBy(() ->
                PfxLoader.load("", resourceLoader))
                .isInstanceOf(ConnectIpsException.class)
                .hasMessageContaining("pfx-path is not configured");
    }
}