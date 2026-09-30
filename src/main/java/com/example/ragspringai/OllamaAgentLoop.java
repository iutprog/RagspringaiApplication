package com.example.ragspringai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Week 1 exercise: the naive agent loop, nothing hidden.
 *
 * Mechanism this implements (same 5 steps from the tutoring session):
 *   1. state  = growing list of messages (this IS the agent's memory)
 *   2. call the model, passing state + available tools
 *   3. parse response: tool call, or final answer?
 *   4. if tool call -> YOUR code executes it (the model never touches real systems)
 *   5. append the result as an observation, loop back to step 2
 *
 * Stop conditions implemented here:
 *   - default: model returns a message with no tool_calls -> print answer, return
 *   - hard cap: MAX_STEPS, enforced by code regardless of what the model wants
 *
 * Requires: org.json on the classpath.
 *   Download: https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
 *   Run:      java -cp json-20240303.jar OllamaAgentLoop.java
 *             (Windows: use ; instead of : if you add more classpath entries)
 *
 * NOTE: I have not run this against your exact Ollama version. If the "tool"
 * role message format below doesn't match what your Ollama build expects,
 * print response.body() raw on the first call and compare field names before
 * assuming the loop logic is wrong — that's the actual debugging skill this
 * exercise is meant to build.
 */
public class OllamaAgentLoop {

    //static final String OLLAMA_URL = "http://localhost:11434/api/chat"; // IPv4 localhost, for Intel Macs
    static final String OLLAMA_URL = "http://[::1]:11434/api/chat";  // IPv6 localhost, for M1/M2 Macs
    static final String MODEL = "llama3.2";
    static final int MAX_STEPS = 5; // the hard-cap stop condition
    static Process mcpProcess;
    static BufferedWriter mcpIn;
    static BufferedReader mcpOut;
    static int nextId = 100;

    public static void main(String[] args) throws Exception {
        startMcpServer();
        
        HttpClient client = HttpClient.newHttpClient();

        // Step 1: state. This list is the entire memory of the agent.
        List<Object> messages = new ArrayList<>();
        // This is the question the agent is trying to answer.
        messages.add(new JSONObject().put("role", "user").put("content",
                "Scan src/main/resources/data/RouteHandleTable.cpp for suspicious casts, then check the migration notes for guidance on handling what you find, and tell me what to do about each one."));
        // The one tool this agent is allowed to use.
        JSONArray tools = new JSONArray()
        .put(new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", "get_current_time")
                        .put("description", "Get the current local date and time")
                        .put("parameters", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject())
                                .put("required", new JSONArray()))))
        .put(new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", "CppCastScanner")
                        .put("description", "Scans a C++ source file for suspicious pointer-to-narrow-integer casts. Read-only — reports findings, does not modify the file.")
                        .put("parameters", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("filePath", new JSONObject()
                                                .put("type", "string")
                                                .put("description", "Path to the .cpp file to scan")))
                                .put("required", new JSONArray().put("filePath")))))
        .put(new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", "MigrationNotesSearch")
                        .put("description", "Searches the Aurora Freight 64-bit migration notes for relevant information.")
                        .put("parameters", new JSONObject()
                                .put("type", "object")
                                .put("properties", new JSONObject()
                                        .put("query", new JSONObject()
                                                .put("type", "string")
                                                .put("description", "The search query to find relevant migration notes.")))
                                .put("required", new JSONArray().put("query")))));

        for (int step = 1; step <= MAX_STEPS; step++) {
            System.out.println("--- step " + step + " ---");

            // Step 2: call the model with current state + tools.
            JSONObject requestBody = new JSONObject()
                    .put("model", MODEL)
                    .put("messages", new JSONArray(messages))
                    .put("tools", tools)
                    .put("stream", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Uncomment while debugging to see the raw shape Ollama actually returns:
            // System.out.println("Raw Ollama response: " + response.body());

            JSONObject responseJson = new JSONObject(response.body());
            JSONObject message = responseJson.getJSONObject("message");

            // Step 3: parse — tool call, or final answer?
            boolean wantsTool = message.has("tool_calls")
                    && message.get("tool_calls") != JSONObject.NULL
                    && message.getJSONArray("tool_calls").length() > 0;

            if (wantsTool) {
                messages.add(message); // keep the assistant's tool-request in history

                JSONArray toolCalls = message.getJSONArray("tool_calls");
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject call = toolCalls.getJSONObject(i);
                    String toolName = call.getJSONObject("function").getString("name");
                    JSONObject arguments = call.getJSONObject("function").getJSONObject("arguments");

                    // Step 4: YOUR code executes the tool. The model cannot do this itself.
                    String result = callMcpTool(toolName, arguments);
                    System.out.println("executed tool: " + toolName + " -> " + result);

                    // Step 5: feed the observation back in.
                    messages.add(new JSONObject().put("role", "tool").put("content", result));
                }
                // loop continues — model sees the tool result on the next call
            } else {
                System.out.println("Final answer: " + message.optString("content", "(no content)"));
                return; // default stop condition
            }
        }

        System.out.println("Stopped: max iterations (" + MAX_STEPS + ") reached.");
    }

    // Launches the MCP subprocess, completes the initialize handshake, and keeps its streams for tool calls.
    static void startMcpServer() throws IOException {
    ProcessBuilder pb = new ProcessBuilder(
        "java", "-cp", System.getProperty("java.class.path"),
        "com.example.ragspringai.MinimalMcpServer"
    );
    mcpProcess = pb.start();
    mcpIn = new BufferedWriter(new OutputStreamWriter(mcpProcess.getOutputStream(), StandardCharsets.UTF_8));
    mcpOut = new BufferedReader(new InputStreamReader(mcpProcess.getInputStream(), StandardCharsets.UTF_8));

    // TODO: build the same "initialize" JSON you tested by hand, write it + "\n" to mcpIn, flush
        JSONObject initializeRequest = new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId++)
            .put("method", "initialize")
            .put("params", new JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject()
                    .put("name", "ollama-agent-loop")
                    .put("version", "0.1.0")));
        mcpIn.write(initializeRequest.toString());
        mcpIn.newLine();
        mcpIn.flush();

    // TODO: read one line back from mcpOut (the initialize response) -- just consume it, nothing to do with it yet
        String initializeResponse = mcpOut.readLine();
        System.out.println("MCP initialize response: " + initializeResponse);

    // TODO: send the "notifications/initialized" line -- no "id" field, and no response will come back for it
        JSONObject initializedNotification = new JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized");
        mcpIn.write(initializedNotification.toString());
        mcpIn.newLine();
        mcpIn.flush();
}

static String callMcpTool(String toolName, JSONObject arguments) throws IOException {
    int id = nextId++;
    JSONObject request = new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "tools/call")
            .put("params", new JSONObject().put("name", toolName).put("arguments", arguments));

    // TODO: write request.toString() + "\n" to mcpIn, flush
        mcpIn.write(request.toString());
        mcpIn.newLine();
        mcpIn.flush();
    // TODO: read one line back from mcpOut, parse as JSONObject
        String responseLine = mcpOut.readLine();
        if (responseLine == null) {
            return "MCP server closed its output before returning a tool response.";
        }
        JSONObject response = new JSONObject(responseLine);
        
    // TODO: if it has "result" -> pull result.content[0].text and return it
    //       if it has "error" instead -> return something describing the error
        if (response.has("result")) {
            JSONObject result = response.getJSONObject("result");
            JSONArray content = result.optJSONArray("content");
            if (content != null && !content.isEmpty()) {
                return content.getJSONObject(0).optString("text", result.toString());
            }
            return result.toString();
        }
        if (response.has("error")) {
            JSONObject error = response.getJSONObject("error");
            return "MCP error " + error.optInt("code", 0) + ": "
                    + error.optString("message", "Unknown error");
        }
        return "Unexpected MCP response: " + response;
}

}