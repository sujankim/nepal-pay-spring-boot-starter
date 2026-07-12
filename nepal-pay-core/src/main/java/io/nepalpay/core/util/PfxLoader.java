package io.nepalpay.core.util;

import io.nepalpay.core.exception.ConnectIpsException;

import java.io.InputStream;

/**
 * Utility class for loading ConnectIPS CREDITOR.pfx file bytes.
 *
 * <p>Extracted from the six auto-configuration classes that previously
 * each contained an identical copy of this logic (D-35/D-51).
 * Centralising here means a bug fix or enhancement only needs to
 * be applied once.
 *
 * <p><strong>Design — Zero Spring dependency:</strong>
 * This class accepts a plain {@link InputStream} — not Spring's
 * {@code ResourceLoader} or {@code Resource}. Callers in the starter
 * modules (where Spring is available) resolve the resource and open
 * the stream, then delegate here for validation and reading.
 * This keeps {@code nepal-pay-core} free of any Spring dependency.
 *
 * <p><strong>Fix (Bug #13):</strong> Uses try-with-resources to
 * guarantee the {@link InputStream} is closed after reading, even if
 * {@code readAllBytes()} throws — preventing file-descriptor leaks
 * on Kubernetes rolling restarts.
 *
 * @author Sujan Lamichhane
 */
public final class PfxLoader {

    /** Utility class — do not instantiate. */
    private PfxLoader() {}

    /**
     * Validates that a pfx-path property is configured.
     *
     * <p>Call this before obtaining a resource from Spring's
     * {@code ResourceLoader} so the user gets a clear error message
     * if the property is missing — not a cryptic Spring resource error.
     *
     * @param pfxPath the configured path value
     * @throws ConnectIpsException if pfxPath is null or blank
     */
    public static void validatePath(String pfxPath) {
        if (pfxPath == null || pfxPath.isBlank()) {
            throw new ConnectIpsException(
                    "ConnectIPS pfx-path is not configured. " +
                            "Set nepalpay.connectips.pfx-path in application.yml. " +
                            "Supported formats: file:/app/CREDITOR.pfx " +
                            "or classpath:CREDITOR.pfx. " +
                            "Contact NCHL at connectips@nchl.com.np " +
                            "to obtain your certificate.");
        }
    }

    /**
     * Reads all bytes from the given {@link InputStream}.
     *
     * <p>The caller is responsible for:
     * <ol>
     *   <li>Validating the path with {@link #validatePath(String)}</li>
     *   <li>Checking the resource exists before opening the stream</li>
     *   <li>Opening the stream — this method owns it and closes it</li>
     * </ol>
     *
     * <p>Uses try-with-resources to close the stream after reading,
     * even if {@code readAllBytes()} throws (Bug #13 fix).
     *
     * @param inputStream open stream to the .pfx file — will be closed
     * @param pfxPath     original path string — used in error messages only
     * @return file contents as a byte array — never null, never empty
     * @throws ConnectIpsException if the stream is null, the file is
     *         empty, or any I/O error occurs
     */
    public static byte[] read(InputStream inputStream, String pfxPath) {
        if (inputStream == null) {
            throw new ConnectIpsException(
                    "ConnectIPS .pfx InputStream is null for path: "
                            + pfxPath);
        }

        try (inputStream) {
            byte[] bytes = inputStream.readAllBytes();

            if (bytes.length == 0) {
                throw new ConnectIpsException(
                        "ConnectIPS .pfx file is empty at path: " + pfxPath +
                                ". Ensure the file is a valid PKCS12 certificate.");
            }

            return bytes;

        } catch (ConnectIpsException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectIpsException(
                    "Failed to load ConnectIPS .pfx file from path: "
                            + pfxPath + ". Error: " + e.getMessage(), e);
        }
    }
}