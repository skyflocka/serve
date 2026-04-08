package org.pytorch.serve.archive.s3;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.FileAlreadyExistsException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.pytorch.serve.archive.utils.ArchiveUtils;
import org.pytorch.serve.archive.utils.InvalidArchiveURLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Various Http helper routines */
public final class HttpUtils {
    private static final Logger logger = LoggerFactory.getLogger(HttpUtils.class);
    private static final int MAX_REDIRECTS = 10;

    private HttpUtils() {}

    /** Copy model from S3 url to local model store */
    public static boolean copyURLToFile(
            List<String> allowedUrls,
            String url,
            File modelLocation,
            boolean s3SseKmsEnabled,
            String archiveName)
            throws FileAlreadyExistsException, IOException, InvalidArchiveURLException {
        if (ArchiveUtils.validateURL(allowedUrls, url)) {
            if (modelLocation.exists()) {
                throw new FileAlreadyExistsException(archiveName);
            }

            if (archiveName.contains("/") || archiveName.contains("\\")) {
                throw new IOException(
                        "Security alert slash or backslash appear in archiveName:" + archiveName);
            }

            // for a simple GET, we have no body so supply the precomputed 'empty' hash
            Map<String, String> headers;
            if (s3SseKmsEnabled) {
                String awsAccessKey = System.getenv("AWS_ACCESS_KEY_ID");
                String awsSecretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
                String regionName = System.getenv("AWS_DEFAULT_REGION");
                if (regionName.isEmpty() || awsAccessKey.isEmpty() || awsSecretKey.isEmpty()) {
                    throw new IOException(
                            "Miss environment variables "
                                    + "AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY or AWS_DEFAULT_REGION");
                }
                copyHttpUrlToFile(
                        allowedUrls,
                        new URL(url),
                        modelLocation,
                        awsAccessKey,
                        awsSecretKey,
                        regionName);
            } else {
                URL endpointUrl = new URL(url);
                if ("http".equalsIgnoreCase(endpointUrl.getProtocol())
                        || "https".equalsIgnoreCase(endpointUrl.getProtocol())) {
                    copyHttpUrlToFile(allowedUrls, endpointUrl, modelLocation, null, null, null);
                } else {
                    FileUtils.copyURLToFile(endpointUrl, modelLocation);
                }
            }
        }
        return false;
    }

    private static void copyHttpUrlToFile(
            List<String> allowedUrls,
            URL endpointUrl,
            File modelLocation,
            String awsAccessKey,
            String awsSecretKey,
            String regionName)
            throws IOException, InvalidArchiveURLException {
        URL currentUrl = endpointUrl;
        int redirectCount = 0;

        while (true) {
            if (redirectCount > MAX_REDIRECTS) {
                throw new IOException("Too many redirects while downloading archive from: " + endpointUrl);
            }

            HttpURLConnection connection = (HttpURLConnection) currentUrl.openConnection();
            Map<String, String> headers = buildHeaders(connection, awsAccessKey, awsSecretKey, regionName);
            connection.setInstanceFollowRedirects(false);
            setHttpConnection(connection, "GET", headers);

            try {
                int statusCode = connection.getResponseCode();
                if (isRedirect(statusCode)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("Redirect response missing Location header for: " + currentUrl);
                    }

                    URL redirectUrl = new URL(currentUrl, location);
                    ArchiveUtils.validateURL(allowedUrls, redirectUrl.toString());
                    currentUrl = redirectUrl;
                    redirectCount += 1;
                    continue;
                }

                FileUtils.copyInputStreamToFile(connection.getInputStream(), modelLocation);
                return;
            } finally {
                connection.disconnect();
            }
        }
    }

    private static Map<String, String> buildHeaders(
            HttpURLConnection connection,
            String awsAccessKey,
            String awsSecretKey,
            String regionName)
            throws IOException {
        Map<String, String> headers = new HashMap<>();
        if (regionName == null) {
            return headers;
        }

        headers.put("x-amz-content-sha256", AWS4SignerBase.EMPTY_BODY_SHA256);
        AWS4SignerForAuthorizationHeader signer =
                new AWS4SignerForAuthorizationHeader(connection.getURL(), "GET", "s3", regionName);
        String authorization =
                signer.computeSignature(
                        headers,
                        null,
                        AWS4SignerBase.EMPTY_BODY_SHA256,
                        awsAccessKey,
                        awsSecretKey);
        headers.put("Authorization", authorization);
        return headers;
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_MOVED_PERM
                || statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                || statusCode == HttpURLConnection.HTTP_SEE_OTHER
                || statusCode == 307
                || statusCode == 308;
    }

    public static void setHttpConnection(
            HttpURLConnection connection, String httpMethod, Map<String, String> headers)
            throws IOException {
        connection.setRequestMethod(httpMethod);

        if (headers != null) {
            for (String headerKey : headers.keySet()) {
                connection.setRequestProperty(headerKey, headers.get(headerKey));
            }
        }
    }

    public static String urlEncode(String url, boolean keepPathSlash)
            throws UnsupportedEncodingException {
        String encoded;
        try {
            encoded = URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logger.error("UTF-8 encoding is not supported.", e);
            throw e;
        }
        if (keepPathSlash) {
            encoded = encoded.replace("%2F", "/");
        }
        return encoded;
    }
}
