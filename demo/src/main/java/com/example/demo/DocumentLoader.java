package com.example.demo;


import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentLoader {

    private Logger logger = LoggerFactory.getLogger(DocumentLoader.class);

    private final EmbeddingStore<TextSegment> embeddingStore;

    public DocumentLoader(EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingStore = embeddingStore;
    }

    public void loadDocument(){
        logger.info("Loading Document");
        Document document = FileSystemDocumentLoader.loadDocument("C:\\Users\\nabil\\Downloads\\demo (1)\\demo\\src\\main\\resources\\unicam_courses.json");
        EmbeddingStoreIngestor.ingest(document,embeddingStore);
        logger.info("Document loaded");
    }
}
