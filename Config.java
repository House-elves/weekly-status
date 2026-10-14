import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "weekly-status");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");

    String githubUser;
    String displayName;
    Set<String> excludeOrgs;
    String gmailAddress;
    String gmailAppPassword;
    String sendTo;
    /** "Send to team" on the desk: the team's list (TEAM_TO), empty when not configured. */
    String teamTo;
    int lookbackDays;

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Run install.sh to set up weekly-status.");
            System.exit(1);
        }

        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }

        Config c = new Config();
        c.githubUser = raw.getOrDefault("GITHUB_USER", "");
        c.displayName = raw.getOrDefault("DISPLAY_NAME", "");
        c.excludeOrgs = parseSet(raw.getOrDefault("EXCLUDE_ORGS", ""));
        c.gmailAddress = raw.getOrDefault("GMAIL_ADDRESS", "");
        c.gmailAppPassword = raw.getOrDefault("GMAIL_APP_PASSWORD", "");
        c.sendTo = raw.getOrDefault("SEND_TO", "");
        c.teamTo = raw.getOrDefault("TEAM_TO", "");
        c.lookbackDays = Integer.parseInt(raw.getOrDefault("LOOKBACK_DAYS", "7"));
        return c;
    }

    void validate(boolean requireEmail) {
        requireField("GITHUB_USER", githubUser);
        requireField("DISPLAY_NAME", displayName);
        if (requireEmail) {
            requireField("GMAIL_ADDRESS", gmailAddress);
            requireField("GMAIL_APP_PASSWORD", gmailAppPassword);
            requireField("SEND_TO", sendTo);
        }
    }

    private static Set<String> parseSet(String csv) {
        return Set.of(csv.split(",")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static void requireField(String name, String value) {
        if (value == null || value.isEmpty() || "CHANGE_ME".equals(value)) {
            System.err.println("Error: " + name + " not configured in " + CONFIG_PATH);
            System.exit(1);
        }
    }
}
