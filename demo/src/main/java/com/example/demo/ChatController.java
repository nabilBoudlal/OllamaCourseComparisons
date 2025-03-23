package com.example.demo;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private Logger logger = LoggerFactory.getLogger(ChatController.class);
    private final ChatLanguageModel chatLanguageModel;

    private final EmbeddingStore<TextSegment> embeddingStore;

    public ChatController(ChatLanguageModel chatLanguageModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingStore = embeddingStore;
    }


    @GetMapping("/chat")
    public String getLLMResponse(@RequestParam("userQuery") String userQuery) {
        ChatService chatService = AiServices.builder(ChatService.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .contentRetriever(EmbeddingStoreContentRetriever.from(embeddingStore))
                .build();

        return chatService.chat(userQuery);
    }


    @GetMapping("/load")
    public void loadDocument() {
        logger.info("Loading document");
        Document document = FileSystemDocumentLoader.loadDocument("C:\\Users\\nabil\\Downloads\\exampleOllama\\exampleOllama\\src\\main\\resources\\CoursesCatalogueUnicam.txt");
        EmbeddingStoreIngestor.ingest(document, embeddingStore);
        logger.info("Document loaded");
    }
}
