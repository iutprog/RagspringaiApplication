package com.example.ragspringai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal hand-rolled MCP server. No SDK. Transport: stdio, newline-delimited JSON-RPC 2.0.
 *
 * Same "nothing hidden" approach as Week 1's Ollama tool loop, applied to the other
 * side of the same idea: MCP standardizes HOW a client discovers and calls tools, so
 * any MCP-aware client can talk to this process without custom per-client integration
 * code. That's the actual value proposition to weigh against a plain REST API later.
 *
 * Methods implemented (the minimum a real client needs):
 *   - initialize                  (request)      -> server capabilities
 *   - notifications/initialized   (notification) -> no response (no "id" in JSON-RPC = no reply)
 *   - tools/list                  (request)      -> the tools this server exposes
 *   - tools/call                  (request)      -> execute a tool, return its result
 *
 * Lifecycle / stop condition: unlike the agent loop (which decides to stop itself),
 * this server just runs until stdin closes (EOF) -- its lifecycle is controlled by
 * whatever process spawned it, not by anything internal to this code.
 *
 * NOTE: this has not been tested against a real MCP client (Claude Desktop, the
 * official MCP Inspector, etc.) -- only against the manual test lines below. If you
 * wire it into a real client later, expect field-name mismatches to debug, same as
 * the Ollama tool_calls quirk earlier. Read the raw bytes before assuming your logic
 * is wrong.
 */


/**
 * Manual test lines (copy/paste into a terminal running this server):
 *
 *   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"manual-test","version":"0.0.1"}}}
 *   {"jsonrpc":"2.0","method":"notifications/initialized"}
 *   {"jsonrpc":"2.0","id":2,"method":"tools/list"}
 *   {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_current_time","arguments":{}}}
 *   {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"CppCastScanner","arguments":{"filePath":"src/main/resources/data/RouteHandleTable.cpp"}}}
 *   {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"MigrationNotesSearch","arguments":{"query":"64-bit migration"}}}
 */

public class MinimalMcpServer {

        // --- Ollama (local) ---
    static final String OLLAMA_BASE = "http://[::1]:11434"; // IPv6 loopback -- same fix as Week 1
    static final String EMBED_MODEL = "nomic-embed-text";   // 768-dim output
    static final String GEN_MODEL = "mistral-nemo";         // change if you prefer qwen2.5/llama3.2

    // --- Postgres (local, in Podman) ---
    static final String JDBC_URL = "jdbc:postgresql://172.20.219.28:5432/rag_demo";
    static final String DB_USER = "postgres";
    static final String DB_PASSWORD = "test";

    // --- Naive pipeline knobs ---
    static final int CHUNK_SIZE = 250; // characters -- deliberately naive, ignores sentence boundaries
    static final int TOP_K = 3;

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        String line;
      
            while ((line = in.readLine()) != null) {
                if(line.isBlank()) continue;

                JSONObject request;
                try {
                    request = new JSONObject(line);
                } catch (Exception e) {
                    continue; //not a valid JSON, ignored in this minimal version
                }

                String method = request.optString("method", "");
                boolean isNotification = !request.has("id"); //JSON-RPC rules: if no "id" field, it's a notification, not a request. no id means no response is expected.

                JSONObject response = new JSONObject().put("jsonrpc", "2.0");

                switch (method) {
                     case "initialize" -> { 
                            response.put("id", request.get("id"));
                            response.put("result", new JSONObject()
                                    .put("protocolVersion", "2024-11-05")
                                    .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                                    .put("serverInfo", new JSONObject()
                                            .put("name", "minimal-mcp-server")
                                            .put("version", "0.1.0")));

                      }
                     case "notifications/initialized" -> {
                        continue; // notification: send nothing back
                      }
                     case "tools/list" -> { 
                            response.put("id", request.get("id"));
                            JSONArray tools = new JSONArray().put(new JSONObject()
                            //get_current_time
                                    .put("name", "get_current_time")
                                    .put("description", "Get the current local date and time")
                                    .put("inputSchema", new JSONObject()
                                            .put("type", "object")
                                            .put("properties", new JSONObject())
                                            .put("required", new JSONArray())))
                            //CppCastScanner
                                    .put(new JSONObject()
                                            .put("name", "CppCastScanner")
                                            .put("description", "Scans a C++ source file for suspicious pointer-to-narrow-integer casts. Read-only — reports findings, does not modify the file.")
                                            .put("inputSchema", new JSONObject()
                                                    .put("type", "object")
                                                    .put("properties", new JSONObject()
                                                            .put("filePath", new JSONObject()
                                                                    .put("type", "string")
                                                                    .put("description", "Path to the .cpp file to scan")))
                                                    .put("required", new JSONArray().put("filePath"))))
                            //MigrationNotesSearch
                                    .put(new JSONObject()
                                            .put("name", "MigrationNotesSearch")
                                            .put("description", "Searches the Aurora Freight 64-bit migration notes for relevant information.")
                                            .put("inputSchema", new JSONObject()
                                                    .put("type", "object")
                                                    .put("properties", new JSONObject()
                                                            .put("query", new JSONObject()
                                                                    .put("type", "string")
                                                                    .put("description", "The search query to find relevant migration notes.")))
                                                    .put("required", new JSONArray().put("query"))));

                            response.put("result", new JSONObject().put("tools", tools));
                     }
                     case "tools/call" -> { 
                            response.put("id", request.get("id"));
                            JSONObject params = request.getJSONObject("params");
                            String toolName = params.getString("name");
        
                            if (toolName.equals("get_current_time")) {
                                String result = LocalDateTime.now().toString();
                                response.put("result", new JSONObject()
                                        .put("content", new JSONArray().put(new JSONObject()
                                                .put("type", "text")
                                                .put("text", result))));
                            } else if (toolName.equals("CppCastScanner")) {
                                JSONObject arguments = params.getJSONObject("arguments");
                                String filePath = arguments.getString("filePath");
                                CastScanner scanner = new CastScanner();
                                try {
                                    List<CastScanner.Finding> findings = scanner.scan(filePath);
                                        StringBuilder resultText = new StringBuilder()
                                            .append("Found ").append(findings.size()).append(" suspicious casts.");
                                        for (CastScanner.Finding finding : findings) {
                                        resultText.append("\nLine ").append(finding.lineNumber())
                                            .append(": ").append(finding.snippet())
                                            .append("\n  -> ").append(finding.reason());
                                        }
                                    response.put("result", new JSONObject()
                                            .put("content", new JSONArray().put(new JSONObject()
                                                    .put("type", "text")
                                                .put("text", resultText.toString()))));
                                } catch (IOException e) {
                                    response.put("error", new JSONObject()
                                            .put("code", -32602)
                                            .put("message", "Error scanning file: " + e.getMessage()));
                                }
                            }else if (toolName.equals("MigrationNotesSearch")) {
                                JSONObject arguments = params.getJSONObject("arguments");
                                String query = arguments.getString("query");
                                try {
                                    HttpClient http = HttpClient.newHttpClient();
                                    String searchResult = searchMigrationNotes(http, query);
                                    response.put("result", new JSONObject()
                                            .put("content", new JSONArray().put(new JSONObject()
                                                    .put("type", "text")
                                                    .put("text", searchResult))));
                                } catch (Exception e) {
                                    response.put("error", new JSONObject()
                                            .put("code", -32602)
                                            .put("message", "Error searching migration notes: " + e.getMessage()));
                                }
                            
                            }
                            else {
                                response.put("error", new JSONObject()
                                        .put("code", -32602)
                                        .put("message", "Unknown tool: " + toolName));
                            }
                     }
                    default -> { 
                         if (isNotification) continue; // unknown notification -- ignore
                         response.put("id", request.opt("id"));
                         response.put("error", new JSONObject()
                                 .put("code", -32601)
                                 .put("message", "Method not found: " + method));
                    }
                }

                out.println(response.toString());
                out.flush();
            }
       
    }


    // Finds the three migration-note chunks most similar to the query using pgvector distance.
            static String searchMigrationNotes(HttpClient http, String query) throws IOException, InterruptedException, SQLException {
                float[] queryEmbedding = embed(http, query);
                try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
                    PreparedStatement stmt = conn.prepareStatement(
                            "SELECT content FROM flagship_migration_notes ORDER BY embedding <=> ?::vector LIMIT 3")) {
                    stmt.setString(1, vectorLiteral(queryEmbedding));
                    try (ResultSet rs = stmt.executeQuery()) {
                        StringBuilder result = new StringBuilder();
                        while (rs.next()) {
                            if (result.length() > 0) result.append("\n---\n");
                            result.append(rs.getString("content"));
                        }
                        return result.length() > 0 ? result.toString() : "No migration notes found for that query.";
                    }
                }
            }

        // Requests a text embedding from Ollama and converts the returned JSON array to float values.
      static float[] embed(HttpClient http, String text) throws IOException, InterruptedException {
        JSONObject body = new JSONObject().put("model", EMBED_MODEL).put("prompt", text);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_BASE + "/api/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        // NOTE: haven't verified this exact response shape against your Ollama version.
        // If this throws, print resp.body() raw and compare field names -- same debugging
        // move as Week 1's tool_calls quirk.
        JSONObject json = new JSONObject(resp.body());
        JSONArray arr = json.getJSONArray("embedding");
        float[] vec = new float[arr.length()];
        for (int i = 0; i < arr.length(); i++) vec[i] = (float) arr.getDouble(i);
        return vec;
    }

    // Formats an embedding as a pgvector literal for use with the SQL vector cast.
    static String vectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        return sb.append("]").toString();
    }

}