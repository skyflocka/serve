package org.pytorch.serve.archive.workflow;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.io.FileUtils;
import org.pytorch.serve.archive.DownloadArchiveException;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class WorkFlowArchiveTest {

    private static final List<String> ALLOWED_URLS_LIST =
            Collections.singletonList("file://.*|http(s)?://.*");

    @BeforeTest
    public void beforeTest() {
        File tmp = FileUtils.getTempDirectory();
        FileUtils.deleteQuietly(new File(tmp, "workflows"));
    }

    @Test
    public void test() throws IOException, DownloadArchiveException, WorkflowException {
        String workflowStore = "src/test/resources/workflows";
        WorkflowArchive archive =
                WorkflowArchive.downloadWorkflow(ALLOWED_URLS_LIST, workflowStore, "smtest.war");
        archive.validate();
        archive.clean();
        Assert.assertEquals(archive.getWorkflowName(), "smtest");
    }

    @Test(
            expectedExceptions = DownloadArchiveException.class,
            expectedExceptionsMessageRegExp =
                    "Failed to download archive from: https://s3\\.amazonaws\\.com/squeezenet_v1\\.1\\.mod")
    public void testAllowedURL() throws WorkflowException, IOException, DownloadArchiveException {
        // test allowed url, return failed to download as file does not exist
        String workflowStore = "src/test/resources/workflows";
        WorkflowArchive.downloadWorkflow(
                ALLOWED_URLS_LIST, workflowStore, "https://s3.amazonaws.com/squeezenet_v1.1.mod");
    }

    @Test(
            expectedExceptions = DownloadArchiveException.class,
            expectedExceptionsMessageRegExp =
                    "Failed to download archive from: https://torchserve\\.pytorch\\.org/mar_files/mnist_non_exist\\.war")
    public void testAllowedMultiUrls()
            throws WorkflowException, IOException, DownloadArchiveException {
        // test multiple urls added to allowed list
        String workflowStore = "src/test/resources/workflows";
        final List<String> customUrlPatternList =
                Arrays.asList(
                        "http(s)?://s3.amazonaws.com.*",
                        "https://torchserve.pytorch.org/mar_files/.*");
        WorkflowArchive.downloadWorkflow(
                customUrlPatternList,
                workflowStore,
                "https://torchserve.pytorch.org/mar_files/mnist_non_exist.war");
    }

    @Test(
            expectedExceptions = WorkflowNotFoundException.class,
            expectedExceptionsMessageRegExp =
                    "Given URL https://torchserve\\.pytorch.org/mar_files/mnist\\.war does not match any allowed URL\\(s\\)")
    public void testBlockedUrl() throws WorkflowException, IOException, DownloadArchiveException {
        // test blocked url
        String workflowStore = "src/test/resources/workflows";
        final List<String> customUrlPatternList =
                Collections.singletonList("http(s)?://s3.amazonaws.com.*");
        WorkflowArchive.downloadWorkflow(
                customUrlPatternList,
                workflowStore,
                "https://torchserve.pytorch.org/mar_files/mnist.war");
    }

    @Test
    public void testRedirectTargetNotRevalidatedAgainstAllowedUrls()
            throws Exception {
        String workflowStore = "build/tmp/test/workflow_store_redirect";
        File workflowStoreDir = new File(workflowStore);
        FileUtils.deleteQuietly(workflowStoreDir);
        workflowStoreDir.mkdirs();

        File warFixture = new File("src/test/resources/workflows/smtest.war");
        byte[] warBytes = Files.readAllBytes(warFixture.toPath());

        HttpServer secondHopServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondHopServer.createContext(
                "/blocked.war",
                exchange -> {
                    exchange.sendResponseHeaders(200, warBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(warBytes);
                    }
                });
        ExecutorService secondHopExecutor = Executors.newSingleThreadExecutor();
        secondHopServer.setExecutor(secondHopExecutor);
        secondHopServer.start();
        int secondPort = secondHopServer.getAddress().getPort();

        HttpServer firstHopServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        firstHopServer.createContext(
                "/allowed.war",
                exchange -> {
                    String location = "http://127.0.0.1:" + secondPort + "/blocked.war";
                    exchange.getResponseHeaders().add("Location", location);
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        ExecutorService firstHopExecutor = Executors.newSingleThreadExecutor();
        firstHopServer.setExecutor(firstHopExecutor);
        firstHopServer.start();
        int firstPort = firstHopServer.getAddress().getPort();

        String allowedPattern = "http://127\\.0\\.0\\.1:" + firstPort + "/.*";
        List<String> strictAllowed = Collections.singletonList(allowedPattern);
        String registrationUrl = "http://127.0.0.1:" + firstPort + "/allowed.war";
        String blockedUrl = "http://127.0.0.1:" + secondPort + "/blocked.war";

        try {
            File downloaded = new File(workflowStore, "allowed.war");
            WorkflowNotFoundException exception =
                    Assert.expectThrows(
                            WorkflowNotFoundException.class,
                            () ->
                                    WorkflowArchive.downloadWorkflow(
                                            strictAllowed, workflowStore, registrationUrl));
            Assert.assertEquals(
                    exception.getMessage(),
                    "Given URL " + blockedUrl + " does not match any allowed URL(s)");
            Assert.assertFalse(
                    downloaded.exists(),
                    "Workflow archive should not be downloaded when a redirect leaves allowed_urls");
        } finally {
            firstHopServer.stop(0);
            secondHopServer.stop(0);
            firstHopExecutor.shutdownNow();
            secondHopExecutor.shutdownNow();
            FileUtils.deleteQuietly(workflowStoreDir);
        }
    }

    @Test
    public void testRedirectMissingLocationHeaderFailsDownload()
            throws Exception {
        String workflowStore = "build/tmp/test/workflow_store_missing_location";
        File workflowStoreDir = new File(workflowStore);
        FileUtils.deleteQuietly(workflowStoreDir);
        workflowStoreDir.mkdirs();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/missing-location.war",
                exchange -> {
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();

        String registrationUrl = "http://127.0.0.1:" + port + "/missing-location.war";
        List<String> strictAllowed =
                Collections.singletonList("http://127\\.0\\.0\\.1:" + port + "/.*");

        try {
            File downloaded = new File(workflowStore, "missing-location.war");
            DownloadArchiveException exception =
                    Assert.expectThrows(
                            DownloadArchiveException.class,
                            () ->
                                    WorkflowArchive.downloadWorkflow(
                                            strictAllowed, workflowStore, registrationUrl));
            Assert.assertTrue(exception.getCause() instanceof IOException);
            Assert.assertEquals(
                    exception.getCause().getMessage(),
                    "Redirect response missing Location header for: " + registrationUrl);
            Assert.assertFalse(
                    downloaded.exists(),
                    "Workflow archive should not be written when a redirect omits Location");
        } finally {
            server.stop(0);
            executor.shutdownNow();
            FileUtils.deleteQuietly(workflowStoreDir);
        }
    }

    @Test
    public void testRedirectLoopFailsAfterMaxRedirects()
            throws Exception {
        String workflowStore = "build/tmp/test/workflow_store_redirect_loop";
        File workflowStoreDir = new File(workflowStore);
        FileUtils.deleteQuietly(workflowStoreDir);
        workflowStoreDir.mkdirs();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/loop.war",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/loop.war");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();

        String registrationUrl = "http://127.0.0.1:" + port + "/loop.war";
        List<String> strictAllowed =
                Collections.singletonList("http://127\\.0\\.0\\.1:" + port + "/.*");

        try {
            File downloaded = new File(workflowStore, "loop.war");
            DownloadArchiveException exception =
                    Assert.expectThrows(
                            DownloadArchiveException.class,
                            () ->
                                    WorkflowArchive.downloadWorkflow(
                                            strictAllowed, workflowStore, registrationUrl));
            Assert.assertTrue(exception.getCause() instanceof IOException);
            Assert.assertEquals(
                    exception.getCause().getMessage(),
                    "Too many redirects while downloading archive from: " + registrationUrl);
            Assert.assertFalse(
                    downloaded.exists(),
                    "Workflow archive should not be written when redirects exceed the limit");
        } finally {
            server.stop(0);
            executor.shutdownNow();
            FileUtils.deleteQuietly(workflowStoreDir);
        }
    }

    @DataProvider(name = "successfulRedirectStatuses")
    public Object[][] successfulRedirectStatuses() {
        return new Object[][] {{301}, {302}, {303}, {307}, {308}};
    }

    @Test(dataProvider = "successfulRedirectStatuses")
    public void testRelativeRedirectWithinAllowedUrlsDownloadsWorkflow(int redirectStatusCode)
            throws Exception {
        String workflowStore = "build/tmp/test/workflow_store_successful_redirect_" + redirectStatusCode;
        File workflowStoreDir = new File(workflowStore);
        FileUtils.deleteQuietly(workflowStoreDir);
        workflowStoreDir.mkdirs();

        File warFixture = new File("src/test/resources/workflows/smtest.war");
        byte[] warBytes = Files.readAllBytes(warFixture.toPath());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/redirect.war",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/downloaded.war");
                    exchange.sendResponseHeaders(redirectStatusCode, -1);
                    exchange.close();
                });
        server.createContext(
                "/downloaded.war",
                exchange -> {
                    exchange.sendResponseHeaders(200, warBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(warBytes);
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.start();
        int port = server.getAddress().getPort();

        String registrationUrl = "http://127.0.0.1:" + port + "/redirect.war";
        List<String> strictAllowed =
                Collections.singletonList("http://127\\.0\\.0\\.1:" + port + "/.*");

        try {
            WorkflowArchive archive =
                    WorkflowArchive.downloadWorkflow(strictAllowed, workflowStore, registrationUrl);
            File redirectedDownload = new File(workflowStore, "redirect.war");
            Assert.assertTrue(redirectedDownload.exists(), "Redirected workflow should be written");
            Assert.assertEquals(archive.getWorkflowName(), "smtest");
            archive.validate();
            archive.clean();
        } finally {
            server.stop(0);
            executor.shutdownNow();
            FileUtils.deleteQuietly(workflowStoreDir);
        }
    }
}
