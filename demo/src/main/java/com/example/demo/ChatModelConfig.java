package com.example.demo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration class for setting up the ChatLanguageModel bean using Ollama.
 * <p>
 * This class configures and provides a {@link ChatLanguageModel} instance
 * using the Ollama chat model with specific parameters for model behavior
 * and connection settings.
 * </p>
 */
@Configuration
public class ChatModelConfig {

    /**
     * Creates and configures the Ollama chat language model bean.
     * <p>
     * The configured model uses the following settings:
     * <ul>
     *   <li>Model name: "llama3.2:latest"</li>
     *   <li>Base URL: "http://localhost:11434" (local Ollama server)</li>
     *   <li>Timeout: 360 seconds</li>
     *   <li>Temperature: 0.3 (for controlled randomness in responses)</li>
     * </ul>
     * </p>
     *
     * @return Configured {@link ChatLanguageModel} instance
     * @see OllamaChatModel
     */
    @Bean
    ChatLanguageModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .modelName("llama3.2_kaggle:latest")
                .baseUrl("http://localhost:11434")
                .timeout(Duration.ofSeconds(360))
                .temperature(0.2)
                .build();
    }
}