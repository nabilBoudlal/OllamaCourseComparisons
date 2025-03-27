package com.example.demo;

import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for setting up the PgVector embedding store.
 * <p>
 * This class configures and provides a {@link PgVectorEmbeddingStore} bean
 * for storing and retrieving vector embeddings in a PostgreSQL database with
 * pgvector extension support.
 * </p>
 */
@Configuration
public class EmbeddingStoreConfig {

    /**
     * Database username loaded from application properties.
     */
    @Value("${spring.datasource.username}")
    private String username;

    /**
     * Database password loaded from application properties.
     */
    @Value("${spring.datasource.password}")
    private String password;

    /**
     * Table name for storing embeddings with default value "course_embeddings".
     */
    @Value("${langchain4j.store.pgvector.table-name:course_embeddings}")
    private String tableName;

    /**
     * Dimension of the vector embeddings with default value 1024.
     */
    @Value("${langchain4j.store.pgvector.dimension:1024}")
    private int dimension;

    /**
     * Creates and configures a PgVector embedding store bean.
     * <p>
     * The embedding store is configured with the following properties:
     * <ul>
     *   <li><strong>host</strong>: Database server host (default: localhost)</li>
     *   <li><strong>port</strong>: Database server port (default: 5432)</li>
     *   <li><strong>database</strong>: Database name (default: postgres)</li>
     *   <li><strong>user</strong>: Database username from properties</li>
     *   <li><strong>password</strong>: Database password from properties</li>
     *   <li><strong>table</strong>: Table name for embeddings (default: course_embeddings)</li>
     *   <li><strong>dimension</strong>: Vector dimension size (default: 1024)</li>
     * </ul>
     * </p>
     *
     * @return Configured {@link PgVectorEmbeddingStore} instance
     * @see PgVectorEmbeddingStore
     */
    @Bean
    public PgVectorEmbeddingStore embeddingStore() {
        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("postgres")
                .user(username)
                .password(password)
                .table(tableName)
                .dimension(dimension)
                .build();
    }
}