#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="$HOME/.config/weekly-status"
CONFIG_FILE="$CONFIG_DIR/config"
SCRIPT_NAME="weekly-status"
INSTALL_DIR="$HOME/.local/share/weekly-status"
BIN_DIR="$HOME/.local/bin"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Weekly GitHub Status — Setup ==="
echo

# --- Prerequisites ---

missing=()
command -v java &>/dev/null || missing+=("java")
command -v jbang &>/dev/null || missing+=("jbang")
command -v gh &>/dev/null || missing+=("gh (GitHub CLI)")
command -v claude &>/dev/null || missing+=("claude (Claude Code CLI)")

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing prerequisites: ${missing[*]}"
    echo "Please install them and re-run this script."
    exit 1
fi

if ! gh auth status &>/dev/null; then
    echo "GitHub CLI is not authenticated. Run 'gh auth login' first."
    exit 1
fi

echo "Prerequisites OK."
echo

# --- Gather config ---

default_gh_user=$(gh api user --jq '.login' 2>/dev/null || echo "")

read -rp "Your GitHub username [$default_gh_user]: " github_user
github_user="${github_user:-$default_gh_user}"

read -rp "Your display name (shown in email subject): " display_name

read -rp "GitHub orgs to exclude (comma-separated, or leave empty): " exclude_orgs

read -rp "Gmail address: " gmail_address
echo
echo "  Gmail App Passwords work with 2FA-enabled accounts."
echo "  Create one at: https://myaccount.google.com/apppasswords"
echo "  Select 'Other' and name it 'weekly-status'."
echo
read -srp "Gmail App Password: " gmail_app_password
echo

read -rp "Send email to (comma-separated addresses) [$gmail_address]: " send_to
send_to="${send_to:-$gmail_address}"

read -rp "Lookback days [7]: " lookback_days
lookback_days="${lookback_days:-7}"

read -rp "Schedule (systemd OnCalendar format) [Fri *-*-* 16:00:00]: " schedule
schedule="${schedule:-Fri *-*-* 16:00:00}"

echo

# --- Write config ---

mkdir -p "$CONFIG_DIR"
cat > "$CONFIG_FILE" <<EOF
GITHUB_USER=$github_user
DISPLAY_NAME=$display_name
EXCLUDE_ORGS=$exclude_orgs
GMAIL_ADDRESS=$gmail_address
GMAIL_APP_PASSWORD=$gmail_app_password
SEND_TO=$send_to
LOOKBACK_DAYS=$lookback_days
SCHEDULE=$schedule
EOF
chmod 600 "$CONFIG_FILE"
echo "Config written to $CONFIG_FILE"

# --- Install Java sources ---

mkdir -p "$INSTALL_DIR"
cp "$SCRIPT_DIR"/*.java "$INSTALL_DIR/"
echo "Java sources installed to $INSTALL_DIR"

# --- Create wrapper script ---

mkdir -p "$BIN_DIR"

JBANG_BIN=$(dirname "$(command -v jbang)" 2>/dev/null || echo "")
JAVA_BIN=$(dirname "$(command -v java)" 2>/dev/null || echo "")

cat > "$BIN_DIR/$SCRIPT_NAME" <<WRAPPER
#!/usr/bin/env bash
export PATH="$JBANG_BIN:$JAVA_BIN:$BIN_DIR:\$PATH"
exec jbang "$INSTALL_DIR/WeeklyStatus.java" "\$@"
WRAPPER
chmod +x "$BIN_DIR/$SCRIPT_NAME"
echo "Wrapper script installed to $BIN_DIR/$SCRIPT_NAME"

# --- Detect OS and install scheduler ---

install_systemd() {
    local systemd_dir="$HOME/.config/systemd/user"
    mkdir -p "$systemd_dir"

    cat > "$systemd_dir/$SCRIPT_NAME.service" <<EOF
[Unit]
Description=Weekly GitHub status draft for Elf Desk

[Service]
Type=oneshot
ExecStart=$BIN_DIR/$SCRIPT_NAME --save --notify
Environment=HOME=$HOME
Environment=PATH=$JBANG_BIN:$JAVA_BIN:$BIN_DIR:/usr/bin:/bin

[Install]
WantedBy=default.target
EOF

    cat > "$systemd_dir/$SCRIPT_NAME.timer" <<EOF
[Unit]
Description=Run weekly GitHub status email on schedule

[Timer]
OnCalendar=$schedule
Persistent=true

[Install]
WantedBy=timers.target
EOF

    systemctl --user daemon-reload
    systemctl --user enable --now "$SCRIPT_NAME.timer"

    if command -v loginctl &>/dev/null; then
        loginctl enable-linger "$USER" 2>/dev/null || true
    fi

    echo "Systemd timer enabled (schedule: $schedule)."
}

install_launchd() {
    local plist_dir="$HOME/Library/LaunchAgents"
    local plist_file="$plist_dir/com.house-elves.weekly-status.plist"
    mkdir -p "$plist_dir"

    # Default: Friday at 4 PM
    local day=5 hour=16 minute=0

    cat > "$plist_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.house-elves.weekly-status</string>
    <key>ProgramArguments</key>
    <array>
        <string>$BIN_DIR/$SCRIPT_NAME</string>
    </array>
    <key>StartCalendarInterval</key>
    <dict>
        <key>Weekday</key>
        <integer>$day</integer>
        <key>Hour</key>
        <integer>$hour</integer>
        <key>Minute</key>
        <integer>$minute</integer>
    </dict>
    <key>StandardOutPath</key>
    <string>$CONFIG_DIR/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>$CONFIG_DIR/stderr.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>$JBANG_BIN:$JAVA_BIN:$BIN_DIR:/usr/local/bin:/usr/bin:/bin</string>
        <key>HOME</key>
        <string>$HOME</string>
    </dict>
</dict>
</plist>
EOF

    launchctl unload "$plist_file" 2>/dev/null || true
    launchctl load "$plist_file"
    echo "Launchd agent installed."
}

case "$(uname -s)" in
    Linux)  install_systemd ;;
    Darwin) install_launchd ;;
    *)      echo "Unsupported OS. Run manually: $BIN_DIR/$SCRIPT_NAME" ;;
esac

echo
echo "=== Setup complete! ==="
echo

# --- Offer preview ---

read -rp "Run a preview now? [Y/n] " run_test
if [[ "${run_test,,}" != "n" ]]; then
    "$BIN_DIR/$SCRIPT_NAME" --preview
fi
