package com.example.ragspringai;

import java.io.*;
import java.util.*;
import java.util.regex.*;

public class CastScanner {

    public record Finding(int lineNumber, String snippet, String reason) {}

    private static final Pattern SUSPICIOUS_CAST =
        Pattern.compile("\\((int|long|short)\\)\\s*\\w+");

    private static final String REASON =
        "C-style cast to a narrow integer type — may truncate a pointer on 64-bit builds.";

    public List<Finding> scan(String filePath) throws IOException {
        List<Finding> findings = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (SUSPICIOUS_CAST.matcher(line).find()) {
                    findings.add(new Finding(lineNumber, line.trim(), REASON));
                }
            }
        }

        return findings;
    }

    public static void main(String[] args) throws IOException {
        CastScanner scanner = new CastScanner();
        for (String path : args) {
            System.out.println("=== " + path + " ===");
            for (Finding f : scanner.scan(path)) {
                System.out.println("Line " + f.lineNumber() + ": " + f.snippet());
                System.out.println("  -> " + f.reason());
            }
        }
    }
}