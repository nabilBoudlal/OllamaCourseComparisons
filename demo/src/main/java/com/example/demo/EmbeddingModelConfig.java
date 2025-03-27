package com.example.demo;

import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * Configuration class for setting up the Ollama embedding model.
 * <p>
 * This class provides a Spring Bean configuration for the Ollama embedding model
 * used to generate vector embeddings for text content. The configured model
 * will be available for dependency injection throughout the application.
 * </p>
 */
@Configuration
public class EmbeddingModelConfig {

    /**
     * Creates and configures an Ollama embedding model bean.
     * <p>
     * The embedding model is configured with the following properties:
     * <ul>
     *   <li><strong>baseUrl</strong>: The URL of the local Ollama server (default: http://localhost:11434)</li>
     *   <li><strong>modelName</strong>: The name of the embedding model to use (bge-m3 recommended)</li>
     *   <li><strong>timeout</strong>: Maximum duration to wait for embedding generation (360 seconds)</li>
     * </ul>
     * </p>
     *
     * @return A configured {@link OllamaEmbeddingModel} instance ready for use
     * @see OllamaEmbeddingModel
     */
    @Bean
    public OllamaEmbeddingModel ollamaEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("bge-m3")
                .timeout(Duration.ofSeconds(360))
                .build();
    }
}