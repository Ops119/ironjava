package dev.ironjava.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ironjava.config.IronJavaProperties;
import dev.ironjava.json.JsonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public interface RecommendationClient {
    Result recommend(Request request);

    record Request(
            String findingId,
            String coordinate,
            String currentVersion,
            String pomExcerpt,
            List<String> advisoryIds,
            String affectedRanges,
            List<String> candidates,
            List<String> evidenceIds
    ) {
    }

    record Usage(int calls, int inputTokens, int outputTokens, double estimatedCostUsd) {
    }

    record Result(RecommendationValidator.Recommendation recommendation, Usage usage) {
    }

    final class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String message) {
            super(message);
        }
    }

    final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException() {
            super("BUDGET_EXCEEDED");
        }
    }
}

@Component
@ConditionalOnProperty(name = "ironjava.model.mode", havingValue = "disabled")
class ServerPolicyRecommendationClient implements RecommendationClient {
    private final RecommendationValidator validator = new RecommendationValidator();

    @Override
    public Result recommend(Request request) {
        String version = request.candidates().get(0);
        String json = """
                {"findingId":"%s","coordinate":"%s","candidateVersion":"%s","summary":"Server policy selected the minimum permitted candidate. No model was called.","compatibilityNotes":["Review the upstream release notes before merging."],"evidenceIds":["%s"]}
                """.formatted(request.findingId(), request.coordinate(), version, request.evidenceIds().get(0)).trim();
        RecommendationValidator.Recommendation recommendation = validator.validate(
                json, request.findingId(), request.coordinate(), Set.copyOf(request.candidates()), Set.copyOf(request.evidenceIds()));
        return new Result(new RecommendationValidator.Recommendation(
                recommendation.findingId(), recommendation.coordinate(), recommendation.candidateVersion(),
                recommendation.summary(), recommendation.compatibilityNotes(), recommendation.evidenceIds(), "SERVER_POLICY"),
                new Usage(0, 0, 0, 0));
    }
}

@Component
@ConditionalOnProperty(name = "ironjava.model.mode", havingValue = "required", matchIfMissing = true)
class HttpRecommendationClient implements RecommendationClient {
    private final IronJavaProperties properties;
    private final RecommendationValidator validator = new RecommendationValidator();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    HttpRecommendationClient(IronJavaProperties properties) {
        this.properties = properties;
    }

    @Override
    public Result recommend(Request request) {
        String key = apiKey();
        if (key.isBlank() || properties.getModel().getEndpoint().isBlank() || properties.getModel().getName().isBlank()) {
            throw new ModelUnavailableException("model provider is not configured");
        }
        String context = JsonCodec.write(request);
        if (context.length() > properties.getLimits().getModelMaxInputChars()) {
            throw new BudgetExceededException();
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.getModel().getName());
        body.put("temperature", 0);
        body.put("max_tokens", properties.getLimits().getModelMaxOutputTokens());
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                "Respond with JSON only. Choose candidateVersion only from the supplied candidates. Do not invent versions. Do not claim that tests passed or that verification succeeded.");
        messages.addObject().put("role", "user").put("content", context);
        body.putObject("response_format").put("type", "json_object");
        int calls = 0;
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            calls++;
            if (calls > properties.getLimits().getModelMaxCalls()) {
                throw new BudgetExceededException();
            }
            try {
                HttpRequest http = HttpRequest.newBuilder(URI.create(properties.getModel().getEndpoint()))
                        .timeout(Duration.ofSeconds(40))
                        .header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    last = new ModelUnavailableException("transient provider status");
                    sleep(attempt);
                    continue;
                }
                if (status >= 400) {
                    throw new RecommendationValidator.RecommendationRejectedException("PROVIDER_REJECTED");
                }
                JsonNode root = mapper.readTree(response.body());
                String content = root.path("choices").path(0).path("message").path("content").asText();
                int input = root.path("usage").path("prompt_tokens").asInt(0);
                int output = root.path("usage").path("completion_tokens").asInt(0);
                if (output > properties.getLimits().getModelMaxOutputTokens()) {
                    throw new BudgetExceededException();
                }
                RecommendationValidator.Recommendation recommendation = validator.validate(
                        content, request.findingId(), request.coordinate(), Set.copyOf(request.candidates()), Set.copyOf(request.evidenceIds()));
                double cost = (input / 1_000_000d) * properties.getModel().getInputPricePerMillion()
                        + (output / 1_000_000d) * properties.getModel().getOutputPricePerMillion();
                return new Result(recommendation, new Usage(calls, input, output, cost));
            } catch (RecommendationValidator.RecommendationRejectedException | BudgetExceededException exception) {
                throw exception;
            } catch (ModelUnavailableException exception) {
                last = exception;
                sleep(attempt);
            } catch (Exception exception) {
                last = new ModelUnavailableException("provider request failed");
                sleep(attempt);
            }
        }
        throw last == null ? new ModelUnavailableException("provider request failed") : last;
    }

    private String apiKey() {
        String inline = properties.getModel().getApiKey();
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String path = properties.getModel().getApiKeyPath();
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (Exception exception) {
            return "";
        }
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(200L * (1L << attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
