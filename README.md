# weekly-status

A House Elf that generates and sends weekly GitHub status emails using [Claude Code](https://claude.com/claude-code).

Fetches your GitHub activity (PRs and issues), uses Claude to write a polished status update, and sends it via Gmail. The email has two sections:

- **Last iteration** — merged PRs and closed issues, grouped by repository
- **Next iteration** — open PRs (in progress) and open issues (upcoming work)

## Prerequisites

- **Java 17+** (via SDKMAN or system package manager)
- **[JBang](https://www.jbang.dev/)** — `sdk install jbang` or `curl -Ls https://sh.jbang.dev | bash`
- **[GitHub CLI](https://cli.github.com/)** (`gh`) — authenticated with `gh auth login`
- **[Claude Code CLI](https://claude.com/claude-code)** (`claude`)
- **Gmail account** with an [App Password](https://myaccount.google.com/apppasswords)

## Install

### Quick install (via JBang)

```bash
jbang app install weekly-status@House-elves/weekly-status
```

Then run `install.sh` for interactive setup (config, schedule).

### Manual install (from source)

```bash
git clone https://github.com/House-elves/weekly-status.git
cd weekly-github-status
./install.sh
```

The installer will:

1. Check prerequisites (java, jbang, gh, claude)
2. Prompt for configuration (name, GitHub user, email, schedule)
3. Write config to `~/.config/weekly-status/config` (chmod 600)
4. Install to `~/.local/bin/weekly-status`
5. Set up a scheduler:
   - **Linux**: systemd timer (default: Friday 4 PM)
   - **macOS**: launchd plist
6. Offer a preview test

## Usage

```bash
# Preview — generate and display without sending
weekly-status --preview

# Send the email
weekly-status
```

## Configuration

Stored in `~/.config/weekly-status/config`:

| Key | Description | Default |
|-----|-------------|---------|
| `GITHUB_USER` | Your GitHub username | — |
| `DISPLAY_NAME` | Name shown in email subject | — |
| `EXCLUDE_ORGS` | Comma-separated orgs to skip | — |
| `GMAIL_ADDRESS` | Gmail sender address | — |
| `GMAIL_APP_PASSWORD` | Gmail App Password | — |
| `SEND_TO` | Email recipients (comma-separated) | — |
| `LOOKBACK_DAYS` | Days of activity to include | `7` |
| `SCHEDULE` | systemd OnCalendar expression | `Fri *-*-* 16:00:00` |

## How it works

1. Fetches PRs and issues authored by you from the last `LOOKBACK_DAYS` via `gh search`
2. Filters out excluded orgs and closed (unmerged) PRs
3. Sends the activity data to Claude Code CLI to generate a plain-text status email
4. Sends the email via Gmail SMTP

## Managing the timer

```bash
# Check when the next email is scheduled
systemctl --user list-timers

# Disable the timer
systemctl --user disable --now weekly-status.timer

# Re-enable the timer
systemctl --user enable --now weekly-status.timer

# Check logs from the last run
journalctl --user -u weekly-status.service
```
