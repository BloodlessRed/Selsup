import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.net.URI;
import java.io.IOException;
import java.util.List;

public class CrptApi {
    private final Semaphore semaphore;
    private final ScheduledExecutorService scheduler;
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final String apiUrl;

    public CrptApi(String apiUrl, TimeUnit timeUnit, long period, int requestLimit) {
        this.semaphore = new Semaphore(requestLimit);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.client = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.apiUrl = apiUrl;

        long periodMillis = timeUnit.toMillis(period);
        scheduler.scheduleAtFixedRate(() -> semaphore.release(requestLimit), periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public void createDocument(Document doc, String signature) throws IOException, InterruptedException {
        semaphore.acquire();

        String jsonBody = objectMapper.writeValueAsString(doc);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to create document: " + response.body());
        }
    }

    public static class Document {
        public String participantInn;
        public String docId;
        public String docStatus;
        public String docType;
        public boolean importRequest;
        public String ownerInn;
        public String producerInn;
        public String productionDate;
        public String productionType;
        public List<Product> products;
        public String regDate;
        public String regNumber;

        public static class Product {
            public String certificateDocument;
            public String certificateDocumentDate;
            public String certificateDocumentNumber;
            public String ownerInn;
            public String producerInn;
            public String productionDate;
            public String tnvedCode;
            public String uitCode;
            public String uituCode;
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    public static void main(String[] args) throws InterruptedException {
        // Set up WireMock server
        WireMockServer wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().port(8080));
        wireMockServer.start();

        WireMock.configureFor("localhost", 8080);
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v3/lk/documents/create"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\"}")));

        // Instantiate CrptApi with 10 requests every 30 seconds
        CrptApi api = new CrptApi("http://localhost:8080/api/v3/lk/documents/create", TimeUnit.SECONDS, 15, 10);

        // Create a sample document
        Document doc = new Document();
        doc.participantInn = "1234567890";
        doc.docId = "doc123";
        doc.docStatus = "NEW";
        doc.docType = "LP_INTRODUCE_GOODS";
        doc.importRequest = true;
        doc.ownerInn = "0987654321";
        doc.producerInn = "1122334455";
        doc.productionDate = "2020-01-23";
        doc.productionType = "PRODUCTION";
        Document.Product product = new Document.Product();
        product.certificateDocument = "string";
        product.certificateDocumentDate = "2020-01-23";
        product.certificateDocumentNumber = "string";
        product.ownerInn = "string";
        product.producerInn = "string";
        product.productionDate = "2020-01-23";
        product.tnvedCode = "string";
        product.uitCode = "string";
        product.uituCode = "string";
        doc.products = List.of(product);
        doc.regDate = "2020-01-23";
        doc.regNumber = "string";

        // Create an ExecutorService to manage concurrent requests
        ExecutorService executor = Executors.newFixedThreadPool(20);

        // Simulate sending concurrent requests to test thread safety
        for (int i = 0; i < 20; i++) {
            final int requestNumber = i + 1;
            executor.submit(() -> {
                try {
                    api.createDocument(doc, "signature");
                    System.out.println("Request " + requestNumber + " sent successfully.");
                } catch (Exception e) {
                    System.out.println("Request " + requestNumber + " failed: " + e.getMessage());
                }
            });
        }

        // Shutdown the executor and wait for termination
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        // Shutdown the scheduler and WireMock server
        api.shutdown();
        wireMockServer.stop();
    }
}
