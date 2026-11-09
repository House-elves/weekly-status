import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Your work in the Sessions elf (~/.config/sessions/sessions) over the week: each session tagged as SESSIONS_TAG says
 * (Work, by default) that you worked in since then, with what you asked and what was done, and what it pushed. Only
 * read, never changed. The status is about the work, so it's given as such: the repo, the goal, and the turns.
 */
public class SessionsWork {

    static final Path SESSIONS = Path.of(System.getProperty("user.home"), ".config", "sessions", "sessions");
    static final ObjectMapper JSON = new ObjectMapper();
    /** Kept small, for the coding agent's prompt. */
    static final int MAX_CHARS = 40_000;

    /** The week's sessions as JSON, an empty array when there are none (or no tag is set). */
    static String since(Instant from, String tag, java.util.Set<String> excludeOrgs) {
        ArrayNode out = JSON.createArrayNode();
        if (tag == null || tag.isBlank() || !Files.isDirectory(SESSIONS)) return out.toString();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(SESSIONS)) {
            s.filter(p -> p.getFileName().toString().matches("s\\d+\\.json")).forEach(files::add);
        } catch (Exception e) {
            System.err.println("Couldn't read your sessions: " + e.getMessage());
            return out.toString();
        }
        int chars = 0;
        for (Path f : files) {
            JsonNode s;
            try {
                s = JSON.readTree(f.toFile());
            } catch (Exception e) {
                continue;
            }
            boolean tagged = false;
            for (JsonNode t : s.path("tags")) tagged |= t.asText().equalsIgnoreCase(tag.strip());
            String repo = s.path("repo").asText("");
            if (!tagged || excludeOrgs.contains(repo.contains("/") ? repo.substring(0, repo.indexOf('/')).toLowerCase() : "")) continue;
            ObjectNode one = JSON.createObjectNode().put("session", s.path("name").asText("")).put("repo", repo)
                    .put("goal", clip(s.path("prompt").asText(""), 600));
            ArrayNode turns = one.putArray("turns");
            for (JsonNode t : s.path("turns")) {
                if (!after(t.path("at").asText(""), from) || !"done".equals(t.path("status").asText())) continue;
                turns.addObject().put("asked", clip(t.path("you").asText(""), 300)).put("done", clip(t.path("reply").asText(""), 900));
            }
            ArrayNode pushes = one.putArray("pushed");
            for (JsonNode p : s.path("pushes")) {
                if (!after(p.path("at").asText(""), from) || !p.path("ok").asBoolean(true)) continue;
                ObjectNode x = pushes.addObject().put("to", p.path("target").asText(""));
                if (p.hasNonNull("pr")) x.put("pr", p.path("pr").asText());
            }
            if (turns.isEmpty() && pushes.isEmpty()) continue;
            // The newest turns matter most: drop the oldest until it fits.
            while (one.toString().length() > MAX_CHARS / 3 && turns.size() > 1) turns.remove(0);
            chars += one.toString().length();
            if (chars > MAX_CHARS) break;
            out.add(one);
        }
        return out.toString();
    }

    static boolean after(String at, Instant from) {
        try {
            return !Instant.parse(at).isBefore(from);
        } catch (Exception e) {
            return false;
        }
    }

    static String clip(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
