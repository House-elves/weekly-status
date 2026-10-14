///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java
//SOURCES GitHubActivity.java
//SOURCES EmailGenerator.java
//SOURCES Notifier.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Command(name = "weekly-status", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Weekly GitHub status email generator using Claude Code")
public class WeeklyStatus implements Callable<Integer> {

    @Option(names = "--preview", description = "Generate and display the email without sending")
    boolean preview;

    @Option(names = "--save", description = "Generate the email and save it as a draft under ~/.config/weekly-status/runs "
            + "(for Elf Desk, which shows it and sends it when you click Send). Sends nothing.")
    boolean save;

    @Option(names = "--notify", description = "With --save: a desktop notification that the draft is ready")
    boolean notify;

    @Option(names = "--send", paramLabel = "ID", description = "Send the saved draft ID; the body is read from stdin "
            + "(your edits), or the saved one if stdin is empty. Records when it was sent.")
    String sendId;

    @Option(names = "--to", defaultValue = "me", description = "With --send: me (SEND_TO) or team (TEAM_TO). Each once per draft.")
    String sendTarget;

    static final Path RUNS = Config.CONFIG_DIR.resolve("runs");
    static final java.util.regex.Pattern RUN_ID = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}");
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMMM dd");
    private static final DateTimeFormatter DISPLAY_FORMAT_YEAR = DateTimeFormatter.ofPattern("MMMM dd, yyyy");
    private static final int RETRY_INTERVAL_SECONDS = 120;
    private static final int MAX_WAIT_MINUTES = 30;

    @Override
    public Integer call() {
        if (sendId != null) return sendSaved();
        if (!waitForNetwork()) {
            System.err.println("No network connectivity after retrying. Aborting.");
            return 1;
        }

        Config config = Config.load();
        config.validate(!preview && !save);

        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(config.lookbackDays);
        String weekStart = weekAgo.format(DISPLAY_FORMAT);
        String weekEnd = today.format(DISPLAY_FORMAT_YEAR);

        System.out.println("Fetching GitHub activity since " + weekAgo + "...");
        GitHubActivity gh = new GitHubActivity(config);
        GitHubActivity.Activity activity = gh.fetch();

        System.out.println("Generating email with Claude...");
        EmailGenerator generator = new EmailGenerator();
        String body = generator.generate(activity.toJson(), config.displayName, weekStart, weekEnd);

        if (body == null) {
            System.err.println("Failed to generate email");
            return 1;
        }

        if (save) {
            String id = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            var draft = JSON.createObjectNode().put("id", id).put("generatedAt", java.time.Instant.now().toString())
                    .put("weekStart", weekStart).put("weekEnd", weekEnd)
                    .put("subject", new Notifier(config).subject(weekStart, weekEnd)).put("to", config.sendTo)
                    .put("teamTo", config.teamTo).put("from", config.gmailAddress)
                    .put("body", body);
            try {
                Files.createDirectories(RUNS);
                Files.writeString(RUNS.resolve(id + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(draft));
            } catch (IOException e) {
                System.err.println("Couldn't save the draft: " + e.getMessage());
                return 1;
            }
            System.out.println("Saved draft " + id);
            if (notify) notifyReady(weekStart, weekEnd);
        } else if (preview) {
            System.out.println("\n" + "=".repeat(60));
            System.out.println(body);
            System.out.println("=".repeat(60));

            try {
                Path previewPath = Path.of("/tmp/weekly-status-preview.txt");
                Files.writeString(previewPath, body);
                System.out.println("\nPreview saved to " + previewPath);
            } catch (IOException ignored) {
            }
        } else {
            System.out.println("Sending email...");
            Notifier notifier = new Notifier(config);
            notifier.send(body, weekStart, weekEnd);
        }

        System.out.println("Done!");
        return 0;
    }

    /** A desktop notification: best effort, a missing notify-send or no session just skips it. */
    private static void notifyReady(String weekStart, String weekEnd) {
        try {
            new ProcessBuilder("notify-send", "--app-name=Elf Desk", "--icon=mail-message-new",
                    "Weekly status ready to send", "Status for " + weekStart + " to " + weekEnd
                            + ". Read it and send it from Elf Desk's Status tab.")
                    .inheritIO().start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("No desktop notification: " + e.getMessage());
        }
    }

    /** --send: the saved draft, with the body from stdin if given, sent and marked sent. */
    private int sendSaved() {
        if (!RUN_ID.matcher(sendId).matches()) {
            System.err.println("Not a draft id: " + sendId);
            return 2;
        }
        Path file = RUNS.resolve(sendId + ".json");
        try {
            if (!sendTarget.equals("me") && !sendTarget.equals("team")) {
                System.err.println("--to is me or team");
                return 2;
            }
            var draft = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(file.toFile());
            var sends = draft.withArray("sends");
            for (var s : sends) {
                if (s.path("target").asText().equals(sendTarget)) {
                    System.err.println("It was already sent to " + s.path("to").asText() + " at " + s.path("at").asText());
                    return 1;
                }
            }
            // Drafts sent before "sends" existed: that one went to SEND_TO.
            if (sends.isEmpty() && draft.hasNonNull("sentAt") && sendTarget.equals("me")) {
                System.err.println("It was already sent at " + draft.path("sentAt").asText());
                return 1;
            }
            Config config = Config.load();
            config.validate(true);
            String to = sendTarget.equals("team") ? config.teamTo : config.sendTo;
            if (to.isBlank()) {
                System.err.println("TEAM_TO isn't set in " + Config.CONFIG_PATH);
                return 1;
            }
            String edited = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            String body = edited.isEmpty() ? draft.path("body").asText() : edited;
            new Notifier(config).sendTo(to, draft.path("subject").asText(), body);
            String at = java.time.Instant.now().toString();
            sends.addObject().put("target", sendTarget).put("to", to).put("at", at).put("body", body);
            draft.put("sentAt", at).put("sentTo", to).put("sentBody", body); // the latest send
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(draft));
            System.out.println("{\"sentTo\": " + JSON.writeValueAsString(to) + "}");
            return 0;
        } catch (java.nio.file.NoSuchFileException e) {
            System.err.println("No such draft: " + sendId);
            return 1;
        } catch (Exception e) {
            System.err.println("Not sent: " + e.getMessage());
            return 1;
        }
    }

    private boolean waitForNetwork() {
        if (isNetworkAvailable()) return true;
        int maxAttempts = (MAX_WAIT_MINUTES * 60) / RETRY_INTERVAL_SECONDS;
        System.out.println("Network not available. Checking every " + RETRY_INTERVAL_SECONDS
                + "s for up to " + MAX_WAIT_MINUTES + " minutes...");
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                TimeUnit.SECONDS.sleep(RETRY_INTERVAL_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isNetworkAvailable()) {
                System.out.println("Network available after " + (attempt * RETRY_INTERVAL_SECONDS) + "s.");
                return true;
            }
        }
        return false;
    }

    private static boolean isNetworkAvailable() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.google.com"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new WeeklyStatus()).execute(args);
        System.exit(exitCode);
    }
}
