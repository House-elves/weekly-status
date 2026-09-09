import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class EmailGenerator {

    String generate(String activityJson, String prodSupportLog, String displayName, String weekStart,
            String weekEnd) {
        String prompt = """
                You are writing a weekly status email for %s, a software engineer, to share with their team.
                Based on the GitHub activity data below, write a plain-text email (no HTML, no styling).

                Format it exactly like this structure:

                Hi,

                Status for %s to %s.

                [2-3 sentence summary of the week's work and what's coming next]

                ## What was done during the last iteration

                # Project: [repository name]

                - [item 1]
                - [item 2]

                # Project: [another repository]

                - [item 1]

                ## What is planned for the next iteration

                # Project: [repository name]

                - [item 1]
                - [item 2]

                ## Production support

                - [item]

                Content guidelines:
                - Write in first person ("I merged...", "I'll be working on...")
                - "What was done" and "What is planned" are top-level sections, with projects listed under each
                - Only include a project under a section if it has items for that section — omit it otherwise
                - All PRs in the data are either merged or open (closed PRs are already filtered out)
                - Merged PRs and closed issues are completed work (last iteration)
                - Open PRs are in-progress work, open issues are planned/upcoming work (next iteration)
                - Consolidate release/version-bump PRs into a single summary line like "Shipped releases 1.0.3 through 1.0.6"
                - Keep it concise — short bullet points
                - Include PR/issue URLs inline where relevant
                - If there's no open work for next iteration, say "No open items"
                - Output ONLY the plain text, no markdown fences or explanation
                - "Production support" summarises the prod-support elf's action log below (Bin Space):
                  cases filed / closed / escalated, grouped as disputes, reports, alerts and council
                  sources, with counts and the notable ones (an escalation, an alert that stayed open).
                  Write it in third person ("The prod-support elf filed..."). Omit the whole section
                  if the log is empty.

                GitHub activity data:
                %s

                prod-support elf action log (this iteration; empty if none):
                %s
                """.formatted(displayName, weekStart, weekEnd, activityJson, prodSupportLog);

        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "-p", "--model", "sonnet");
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

            return stdout.strip();
        } catch (IOException | InterruptedException e) {
            System.err.println("Claude error: " + e.getMessage());
            return null;
        }
    }
}
