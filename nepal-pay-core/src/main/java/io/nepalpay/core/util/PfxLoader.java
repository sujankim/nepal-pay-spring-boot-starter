package io.nepalpay.core.util;

import io.nepalpay.core.exception.ConnectIpsException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Utility class for loading ConnectIPS CREDITOR.pfx files.
 *
 * <p>Extracted from the six auto-configuration classes that previously
 * each contained an identical copy of this logic (D-35/D-51).
 * Centralising here means a bug fix or enhancement only needs to
 * be applied once.
 *
 * <p><strong>Fix (Bug #13):</strong> Uses try-with-resources to
 * guarantee the {@link java.io.InputStream} is closed after reading,
 * even if {@code readAllBytes()} throws — preventing file-descriptor
 * leaks on repeated application restarts.
 *
 * <p>This class has zero Spring dependency beyond
 * {@link ResourceLoader} — it lives in {@code nepal-pay-core}
 * so all three starters (Boot 3, Boot 4, reactive) can share it
 * without cross-module dependencies.
 *
 * @author Sujan Lamichhane
 */
public final class PfxLoader {

    /** Utility class — do not instantiate. */
    private PfxLoader() {}

    /**
     * Load CREDITOR.pfx file bytes from a Spring Resource path.
     *
     * <p>Supported path formats:
     * <ul>
     *   <li>{@code file:/app/CREDITOR.pfx} — absolute path on server</li>
     *   <li>{@code classpath:CREDITOR.pfx} — inside JAR (not recommended
     *       for production)</li>
     * </ul>
     *
     * <p>Fails fast at application startup with a clear error if the file
     * is missing, empty, or the path is not configured — rather than
     * silently failing at the first payment attempt.
     *
     * @param pfxPath        Spring Resource path to the .pfx file
     * @param resourceLoader Spring ResourceLoader
     * @return file contents as a byte array — never null, never empty
     * @throws ConnectIpsException if the path is blank, the file does
     *         not exist, the file is empty, or any I/O error occurs
     */
    public static byte[] load(
            String pfxPath, ResourceLoader resourceLoader) {

        if (pfxPath == null || pfxPath.isBlank()) {
            throw new ConnectIpsException(
                    "ConnectIPS pfx-path is not configured. " +
                            "Set nepalpay.connectips.pfx-path in application.yml. " +
                            "Supported formats: file:/app/CREDITOR.pfx " +
                            "or classpath:CREDITOR.pfx. " +
                            "Contact NCHL at connectips@nchl.com.np " +
                            "to obtain your certificate.");
        }

        try {
            Resource resource = resourceLoader.getResource(pfxPath);

            if (!resource.exists()) {
                throw new ConnectIpsException(
                        "ConnectIPS .pfx file not found at path: " + pfxPath +
                                ". Ensure the file exists at this location " +
                                "on your server.");
            }

            //  try-with-resources — prevents InputStream file
            // descriptor leak on Kubernetes rolling restarts (Bug #13)
            final byte[] bytes;
            try (var inputStream = resource.getInputStream()) {
                bytes = inputStream.readAllBytes();
            }

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