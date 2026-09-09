import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the prod-support House Elf did this week, read straight from its
 * append-only actions.log (one line per action, "[yyyy-MM-dd HH:mm:ss z]
 * action case detail"). Missing file = the elf is not installed here, and
 * the email simply has no production-support section.
 */
public class ProdSupportActivity {

    static final Path ACTIONS_LOG = Path.of(
            System.getenv().getOrDefault("XDG_STATE_HOME",
                    System.getProperty("user.home") + "/.local/state"))
            .resolve("prod-support").resolve("actions.log");

    private static final Pattern LINE = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2}) [^\\]]*\\]\\s+(.*)$");

    /** The log lines dated within the lookback window, oldest first; empty if none. */
    static List<String> since(LocalDate from) {
        List<String> out = new ArrayList<>();
        if (!Files.exists(ACTIONS_LOG)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(ACTIONS_LOG)) {
                Matcher m = LINE.matcher(line.strip());
                if (!m.matches()) {
                    continue;
                }
                if (!LocalDate.parse(m.group(1)).isBefore(from)) {
                    out.add(m.group(1) + " " + m.group(2));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("could not read " + ACTIONS_LOG + ": " + e.getMessage());
        }
        return out;
    }
}
