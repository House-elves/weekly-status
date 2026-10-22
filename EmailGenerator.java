import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class EmailGenerator {

    String generate(String activityJson, String displayName, String weekStart, String weekEnd) {
        String prompt = """
                You are writing a weekly status email for %s, a software engineer, to share with their team.
                Based on the GitHub activity data below, write a plain-text email (no HTML, no styling).

                Format it exactly like this structure:

                Hi,

                Status for %s to %s.

                [1-2 sentence summary of what was done this week]

                ## What I have done

                # Project: [repository name]

                - [item 1]
                - [item 2]

                # Project: [another repository]

                - [item 1]

                Content guidelines:
                - Write in first person ("I merged...", "I fixed...")
                - Only report work that was done: the data holds merged PRs and closed issues only
                - Do not add a section about upcoming or planned work, and don't mention what's next
                - List each project that has items, and only those
                - Consolidate release/version-bump PRs into a single summary line like "Shipped releases 1.0.3 through 1.0.6"
                - Keep it concise — short bullet points
                - Include PR/issue URLs inline where relevant
                - If there is nothing in the data, write "No completed items this week" under the section
                - Output ONLY the plain text, no markdown fences or explanation

                GitHub activity data:
                %s
                """.formatted(displayName, weekStart, weekEnd, activityJson);

        try {
            // Locked down: what it reads includes other people's words (titles, comments), so Claude gets no tools
            // (built in or MCP), none of your settings, and an empty folder of its own.
            ProcessBuilder pb = new ProcessBuilder("claude", "-p", "--model", "sonnet", "--tools", "", "--strict-mcp-config",
                    "--setting-sources", "").directory(java.nio.file.Files.createTempDirectory("elf-claude-").toFile());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            try (var os = process.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("Claude timed out");
                return null;
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                System.err.println("Claude failed: " + stderr);
                return null;
            }

            // Strip markdown fences if present
            if (stdout.startsWith("```")) stdout = stdout.substring(stdout.indexOf('\n') + 1);
            if (stdout.endsWith("```")) stdout = stdout.substring(0, stdout.lastIndexOf("```"));

            // The email starts at "Hi,": anything before it (a greeting a global CLAUDE.md asks for) isn't the email.
            int hi = stdout.indexOf("Hi,");
            if (hi > 0) stdout = stdout.substring(hi);

            return stdout.strip();
        } catch (IOException | InterruptedException e) {
            System.err.println("Claude error: " + e.getMessage());
            return null;
        }
    }
}
