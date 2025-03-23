package com.example.demo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ChatModelConfig {

    @Bean
    ChatLanguageModel chatLanguageModel(){
        return OllamaChatModel.builder()
                .modelName("llama3.2_fineTuned")
                .baseUrl("http://localhost:11434")
                .timeout(Duration.ofSeconds(360))
                .build();
    }
}
