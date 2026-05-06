# weekly-github-status

A weekly status email generator that pulls your GitHub activity (PRs and issues), uses Claude to write a polished HTML status update, and sends it via Gmail.

The email has two sections:
- **Last Week** — merged PRs and closed issues, grouped by repository
- **Next Week** — open PRs (in progress) and open issues (upcoming work)

Runs automatically every Monday at 9 AM via a systemd timer.

## Prerequisites

- **Linux** with systemd (user timers)
- **Python 3** with pip
- **[GitHub CLI](https://cli.github.com/)** (`gh`) — authenticated
- **Gmail** account with [App Password](https://myaccount.google.com/apppasswords) (requires 2FA)
- **[Anthropic API key](https://console.anthropic.com/settings/keys)**

## Install

```bash
git clone https://github.com/phillip-kruger/weekly-github-status.git
cd weekly-github-status
./install.sh
```

The installer will:
1. Check prerequisites
2. Install the `anthropic` Python package
3. Prompt you for your details (name, GitHub username, Gmail, API keys)
4. Write a config file to `~/.config/weekly-status/config`
5. Install the script to `~/.local/bin/`
6. Set up and enable a systemd timer for every Monday at 9 AM
7. Optionally run a preview so you can see the email before it goes live

## Usage

```bash
# Send the email now
weekly-github-status

# Preview in browser without sending
weekly-github-status --preview
```

## Configuration

All settings are in `~/.config/weekly-status/config`:

```
GITHUB_USER=your-github-username
DISPLAY_NAME=Your Name
EXCLUDE_ORGS=org-to-hide,another-org
GMAIL_ADDRESS=you@gmail.com
GMAIL_APP_PASSWORD=xxxx xxxx xxxx xxxx
ANTHROPIC_API_KEY=sk-ant-...
SEND_TO=you@gmail.com,team@company.com
```

| Key | Required | Description |
|-----|----------|-------------|
| `GITHUB_USER` | Yes | Your GitHub username |
| `DISPLAY_NAME` | Yes | Name shown in the email header and subject |
| `EXCLUDE_ORGS` | No | Comma-separated GitHub orgs to exclude from the report |
| `GMAIL_ADDRESS` | Yes | Gmail address to send from |
| `GMAIL_APP_PASSWORD` | Yes | Gmail App Password (not your regular password) |
| `ANTHROPIC_API_KEY` | Yes | API key from [Anthropic Console](https://console.anthropic.com) |
| `SEND_TO` | Yes | Comma-separated recipient email addresses |

## Managing the timer

```bash
# Check when the next email is scheduled
systemctl --user list-timers

# Disable the timer
systemctl --user disable --now weekly-github-status.timer

# Re-enable the timer
systemctl --user enable --now weekly-github-status.timer

# Check logs from the last run
journalctl --user -u weekly-github-status.service
```

## How it works

1. Uses `gh search prs` and `gh search issues` to fetch your activity from the past 7 days
2. Filters out closed (unmerged) PRs and excluded orgs
3. Sends the data to Claude (Sonnet) to generate a well-formatted HTML email
4. Sends the email via Gmail SMTP
