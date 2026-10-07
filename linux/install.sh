#!/bin/sh
# Install a verified release without replacing device state or configuration.
#
# A release is installed into its own directory, then `current` is switched to
# it. If the service was running, it is started on the new release and must
# answer `hermes-gadget-device status` within a short time; otherwise `current`
# goes back to the previous release and the service restarts on that. Older
# releases beyond the newest three are removed, except the previous one.
set -eu
umask 022

if [ "$(id -u)" -ne 0 ]; then
    echo 'Run this installer with sudo.' >&2
    exit 1
fi
source_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
cd "$source_dir"
sha256sum --check --status SHA256SUMS
python3 -c '
import json, platform, re
p = json.load(open("package.json"))
arch = {"aarch64": "arm64", "x86_64": "amd64"}.get(platform.machine())
if p["architecture"] != arch:
    raise SystemExit("This package does not match the computer architecture")
if not re.fullmatch(r"[0-9.]+-[0-9a-f]{12}", p["release_id"]):
    raise SystemExit("Invalid package release ID")
if not re.fullmatch(r"hermes_gadget-[0-9.]+-py3-none-any.whl", p["wheel"]):
    raise SystemExit("Invalid package wheel name")
'
release_id=$(python3 -c 'import json; print(json.load(open("package.json"))["release_id"])')
wheel=$(python3 -c 'import json; print(json.load(open("package.json"))["wheel"])')
releases=/opt/hermes-gadget/releases
destination=$releases/$release_id
health_timeout=${HERMES_GADGET_HEALTH_TIMEOUT:-30}
keep_releases=3
install -d -m 755 "$releases"
exec 9>/opt/hermes-gadget/install.lock
flock -n 9 || { echo 'Another installer is running.' >&2; exit 1; }

if [ ! -f "$destination/.installed" ]; then
    # A directory without the marker is a failed earlier attempt: start over.
    rm -rf "$destination"
    install -d -m 755 "$destination"
    # Anything that fails before the marker leaves no half-built release behind.
    discard_unfinished() {
        status=$?
        if [ ! -f "$destination/.installed" ]; then
            rm -rf "$destination"
            echo "Installation of $release_id failed and was removed; fix the cause and run the installer again." >&2
        fi
        exit "$status"
    }
    trap discard_unfinished EXIT
    install -m 644 libhgsim.so "$destination/libhgsim.so"
    install -m 644 package.json "$destination/package.json"
    install -d -m 755 "$destination/licenses"
    cp LICENSE NOTICE THIRD_PARTY_NOTICES.md "$destination/licenses/"
    cp -R LICENSES "$destination/licenses/"
    python3 -m venv --system-site-packages "$destination/venv"
    # The dependencies are the exact files CI tested: pinned versions, checked against
    # their hashes, wheels only (no build step on the Pi). The wheel itself was built
    # from this release and ships in the archive.
    "$destination/venv/bin/python" -m pip install --require-hashes --only-binary=:all: \
        -r "$source_dir/requirements.txt"
    "$destination/venv/bin/python" -m pip install --no-deps --no-index "$source_dir/$wheel"
    HGSIM_LIBRARY="$destination/libhgsim.so" "$destination/venv/bin/python" -c \
        'from hermes_gadget.sim.native import load_library; load_library()'
    touch "$destination/.installed"
    trap - EXIT
fi
cmp package.json "$destination/package.json"
HGSIM_LIBRARY="$destination/libhgsim.so" "$destination/venv/bin/python" -c \
    'from hermes_gadget.sim.native import load_library; load_library()'

if ! getent passwd hermes-gadget >/dev/null; then
    useradd --system --user-group --home-dir /var/lib/hermes-gadget --shell /usr/sbin/nologin hermes-gadget
fi
for group in audio gpio; do
    if getent group "$group" >/dev/null; then
        usermod -a -G "$group" hermes-gadget
    fi
done
install -d -m 700 -o hermes-gadget -g hermes-gadget /var/lib/hermes-gadget
install -d -m 750 -o root -g hermes-gadget /etc/hermes-gadget
if [ ! -e /etc/hermes-gadget/config.json ]; then
    printf '%s\n' '{"server":"ws://127.0.0.1:8765/gadget","name":"Pi Gadget"}' > /etc/hermes-gadget/config.json
    chown root:hermes-gadget /etc/hermes-gadget/config.json
    chmod 640 /etc/hermes-gadget/config.json
fi

# Stop only after the new environment has installed and loaded successfully.
was_active=false
if systemctl is-active --quiet hermes-gadget.service; then
    was_active=true
    systemctl stop hermes-gadget.service
fi
previous=
if [ -L /opt/hermes-gadget/current ]; then
    previous=$(readlink -f /opt/hermes-gadget/current)
    if [ "$previous" != "$destination" ]; then
        ln -sfn "$previous" /opt/hermes-gadget/previous
    fi
fi
rm -f /opt/hermes-gadget/current.new
ln -s "$destination" /opt/hermes-gadget/current.new
mv -Tf /opt/hermes-gadget/current.new /opt/hermes-gadget/current
install -m 644 "$source_dir/hermes-gadget.service" /etc/systemd/system/hermes-gadget.service
install -m 755 "$source_dir/hermes-gadget-device" /usr/local/bin/hermes-gadget-device
systemctl daemon-reload
if [ "$was_active" = true ]; then
    systemctl start hermes-gadget.service
    # The new release must come up and answer on its control socket.
    waited=0
    until hermes-gadget-device status >/dev/null 2>&1; do
        if [ "$waited" -ge "$health_timeout" ]; then
            echo "The service did not answer within ${health_timeout}s on $release_id." >&2
            if [ -n "$previous" ] && [ "$previous" != "$destination" ]; then
                ln -sfn "$previous" /opt/hermes-gadget/current.new
                mv -Tf /opt/hermes-gadget/current.new /opt/hermes-gadget/current
                systemctl restart hermes-gadget.service
                echo "Went back to $(basename "$previous"). See: journalctl -u hermes-gadget -n 50" >&2
            else
                echo 'There is no previous release to go back to. See: journalctl -u hermes-gadget -n 50' >&2
            fi
            exit 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
fi

# Keep the newest releases plus whatever current and previous point at.
current_target=$(readlink -f /opt/hermes-gadget/current)
previous_target=$(readlink -f /opt/hermes-gadget/previous 2>/dev/null || true)
find "$releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p
' | sort -rn | cut -d' ' -f2- |
    tail -n +$((keep_releases + 1)) | while read -r path; do
    if [ "$path" != "$current_target" ] && [ "$path" != "$previous_target" ]; then
        rm -rf "$path"
        echo "Removed old release $(basename "$path")."
    fi
done

echo "Installed $release_id. Configuration and device identity were preserved."
echo 'Edit /etc/hermes-gadget/config.json, then run: sudo systemctl enable --now hermes-gadget'
