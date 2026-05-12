package com.vadim.devops.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vadim.devops.config.DevopsProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${devops.search.tavily-api-key:}')")
public class WebSearchTool {

    private static final String TAVILY_URL = "https://api.tavily.com/search";

    private final RestClient http;
    private final String apiKey;

    public WebSearchTool(DevopsProperties props) {
        this.apiKey = props.search().tavilyApiKey();
        this.http = RestClient.create();
    }

    @Tool(description = """
            Поиск в интернете через Tavily. Используй когда:
            - нужно найти документацию по ошибке/исключению
            - нужно найти решение известной проблемы (например, "java.lang.OutOfMemoryError solutions")
            - нужно проверить актуальные CVE или известные баги библиотек
            - оператор просит найти что-то в интернете
            Возвращает топ-5 результатов с URL и текстом.
            """)
    public String search(String query) {
        try {
            var response = http.post()
                    .uri(TAVILY_URL)
                    .header("Content-Type", "application/json")
                    .body(new SearchRequest(apiKey, query, 5, "basic"))
                    .retrieve()
                    .body(SearchResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return "Ничего не найдено.";
            }

            var result = response.results().stream()
                    .map(r -> "### %s\nURL: %s\n%s".formatted(r.title(), r.url(), r.content()))
                    .collect(Collectors.joining("\n\n---\n\n"));

            return result;
        } catch (Exception e) {
            return "Ошибка поиска: " + e.getMessage();
        }
    }

    record SearchRequest(
            String api_key,
            String query,
            int max_results,
            String search_depth
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResult(String title, String url, String content) {}
}
