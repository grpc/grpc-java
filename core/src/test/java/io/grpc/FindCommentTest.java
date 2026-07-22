package io.grpc;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class FindCommentTest {
    @Test
    public void findComment() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("/usr/local/google/home/agrawalabhi/IdeaProjects/grpc-java/.agents/references/all_scraped_maintainer_comments.jsonl"));
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.toLowerCase().contains("child") || line.contains("plugin")) {
                sb.append(line).append("\n");
            }
        }
        if (sb.length() == 0) sb.append("NO MATCHES FOUND");
        Files.write(Paths.get("/usr/local/google/home/agrawalabhi/IdeaProjects/grpc-java/search_result2.txt"), sb.toString().getBytes());
    }
}
