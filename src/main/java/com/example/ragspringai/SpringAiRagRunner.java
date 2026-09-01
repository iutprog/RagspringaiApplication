package com.example.ragspringai;


import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
 
/**
 * Week 2, second half: the same RAG workflow as the naive pipeline, rebuilt with
 * Spring AI's VectorStore abstraction instead of manual HTTP calls and raw SQL.
 *
 * This version keeps the prompt construction explicit on purpose, so the comparison
 * stays focused on the actual abstraction boundary: chunking, embedding, storage,
 * and retrieval. QuestionAnswerAdvisor is intentionally not used here, even though
 * the dependency is on the classpath.
 *
 * The program reads dispatch_system_notes.txt, splits the document with
 * TokenTextSplitter, clears the PgVector table to avoid duplicate rows on rerun,
 * stores the resulting chunks in the vector store, retrieves the top 3 matches for
 * the question, and then passes the retrieved context plus the question to the
 * chat model for generation.
 *
 * Note: Spring AI APIs evolve quickly. If the code does not compile against the
 * current version, the compiler output is the ground truth for the exact API shape.
 */
@Configuration
public class SpringAiRagRunner {
 
    @Bean
    CommandLineRunner runSpringAiRag(VectorStore vectorStore, ChatModel chatModel, JdbcTemplate jdbcTemplate) {
        return args -> {
            // 1. Load the SAME document as the naive pipeline -- fair comparison.
            String text = Files.readString(Path.of("dispatch_system_notes.txt"));
            Document sourceDocument = new Document(text);
 
            // 2. Chunk -- Spring AI's token-aware splitter, NOT naive fixed-character
            //    slicing. Compare this chunk list against your hand-rolled 250-char
            //    version's chunk 0/1 and chunk 3/4 (the ones that split words in half).
            TokenTextSplitter splitter = new TokenTextSplitter();
            List<Document> chunks = splitter.apply(List.of(sourceDocument));
            System.out.println("=== Spring AI produced " + chunks.size() + " chunks ===");
            for (Document d : chunks) {
                System.out.println("[len=" + d.getText().length() + "] " + d.getText().replace("\n", " \\n "));
            }
 
            // 3. Embed + store -- ONE line. Compare against your naive pipeline's
            //    explicit embed() HTTP call plus hand-written INSERT ... ::vector SQL.
            //
            // NOTE: VectorStore has no automatic "clear before insert" behavior --
            // the abstraction does NOT protect you from duplicate-chunk accumulation
            // on rerun. You hit this for real: 2 rows in vector_store_spring_ai after
            // 2 runs, for what should be 1 unique chunk. Truncate first for a clean
            // run, same discipline your naive pipeline already had -- reaching past
            // the abstraction here on purpose, since VectorStore's own delete API
            // needs IDs or a filter expression we don't have a reliable version-
            // matched signature for.
            jdbcTemplate.execute("TRUNCATE TABLE vector_store_spring_ai");
            vectorStore.add(chunks);
            System.out.println("\n=== stored " + chunks.size() + " chunks via VectorStore.add() ===");
 
            // 4. Retrieve -- ONE line. Compare against your naive pipeline's explicit
            //    embed() call plus hand-written SELECT ... <=> ... SQL. Notice what
            //    is NOT exposed here that your raw SQL version showed you directly:
            //    no per-result distance score by default.
            String question = "Does the migrated build support loading the full national address dataset?";
            List<Document> retrieved = vectorStore.similaritySearch(
                    SearchRequest.builder().query(question).topK(3).build());
 
            System.out.println("\n=== Spring AI retrieved (top 3, no distance shown) ===");
            for (Document d : retrieved) {
                System.out.println(d.getText().replace("\n", " \\n "));
            }
 
            // 5. Build the prompt manually -- same as the naive pipeline -- so this
            //    part of the comparison stays fair.
            String context = retrieved.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n- ", "- ", ""));
            String prompt = "Use the following context to answer the question. "
                    + "If the context does not contain the answer, say you don't know.\n\nContext:\n"
                    + context + "\n\nQuestion: " + question;
 
            // 6. Generate.
            String answer = chatModel.call(prompt);
            System.out.println("\n=== final answer ===\n" + answer);
        };
    }
}