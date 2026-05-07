#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="$HOME/.config/weekly-status"
CONFIG_FILE="$CONFIG_DIR/config"
SCRIPT_NAME="weekly-github-status"
INSTALL_DIR="$HOME/.local/bin"
SYSTEMD_DIR="$HOME/.config/systemd/user"
SCRIPT_SOURCE="$(cd "$(dirname "$0")" && pwd)/$SCRIPT_NAME"

echo "=== Weekly GitHub Status — Setup ==="
echo

# --- Prerequisites ---

missing=()
command -v python3 &>/dev/null || missing+=("python3")
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

read -rp "Your display name (e.g. Phillip): " display_name
read -rp "GitHub username [$default_gh_user]: " github_user
github_user="${github_user:-$default_gh_user}"

read -rp "Gmail address: " gmail_address
read -srp "Gmail App Password (get one at https://myaccount.google.com/apppasswords): " gmail_app_password
echo
read -rp "Send email to (comma-separated addresses) [$gmail_address]: " send_to
send_to="${send_to:-$gmail_address}"
read -rp "GitHub orgs to exclude (comma-separated, or leave empty): " exclude_orgs
read -rp "Schedule (systemd OnCalendar format) [Mon *-*-* 09:00:00]: " schedule
schedule="${schedule:-Mon *-*-* 09:00:00}"

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
SCHEDULE=$schedule
EOF
chmod 600 "$CONFIG_FILE"
echo "Config written to $CONFIG_FILE"

# --- Install script ---

mkdir -p "$INSTALL_DIR"
cp "$SCRIPT_SOURCE" "$INSTALL_DIR/$SCRIPT_NAME"
chmod +x "$INSTALL_DIR/$SCRIPT_NAME"
echo "Script installed to $INSTALL_DIR/$SCRIPT_NAME"

# --- Install systemd units ---

mkdir -p "$SYSTEMD_DIR"

cat > "$SYSTEMD_DIR/$SCRIPT_NAME.service" <<EOF
[Unit]
Description=Weekly GitHub status email

[Service]
Type=oneshot
ExecStart=%h/.local/bin/$SCRIPT_NAME
Environment=HOME=%h
Environment=PATH=%h/.local/bin:/usr/bin:/bin

[Install]
WantedBy=default.target
EOF

cat > "$SYSTEMD_DIR/$SCRIPT_NAME.timer" <<EOF
[Unit]
Description=Run weekly GitHub status email every Monday at 9 AM

[Timer]
OnCalendar=$schedule
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now "$SCRIPT_NAME.timer"
echo "Systemd timer enabled (every Monday at 9 AM)."

echo
echo "=== Setup complete! ==="
echo

# --- Offer test ---

read -rp "Run a preview test now? [Y/n] " run_test
if [[ "${run_test,,}" != "n" ]]; then
    "$INSTALL_DIR/$SCRIPT_NAME" --preview
fi
