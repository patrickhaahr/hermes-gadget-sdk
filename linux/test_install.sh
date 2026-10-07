#!/bin/sh
# Run only in a disposable CI container, never on a user's installed device.
set -eu
if [ ! -f /.dockerenv ]; then
    echo 'The install test requires a disposable Docker container.' >&2
    exit 1
fi
archive=$1
apt-get update -qq
apt-get install -y --no-install-recommends python3-venv libportaudio2 ca-certificates
mkdir /package
tar -xzf "$archive" -C /package
cd /package/hermes-gadget-*-linux-*
# The container has no init system. This stand-in for systemctl records every call and
# runs the service as a background process, so the installer's start, stop, health check
# and rollback paths run for real. FAKE_START_FAILS makes `start` do nothing, which is
# what a release that crashes at startup looks like to the installer.
mkdir /test-bin
cat > /test-bin/systemctl <<'SCRIPT'
#!/bin/sh
echo "$*" >> /systemctl.log
stop() { if [ -f /service.pid ]; then kill "$(cat /service.pid)" 2>/dev/null || true; rm -f /service.pid; sleep 1; fi; }
start() {
    [ -f /service.pid ] && return 0
    HGSIM_LIBRARY=/opt/hermes-gadget/current/libhgsim.so runuser -u hermes-gadget -- \
        /opt/hermes-gadget/current/venv/bin/hermes-gadget linux --state-dir /var/lib/hermes-gadget \
        run --config /etc/hermes-gadget/config.json >> /service.log 2>&1 &
    echo $! > /service.pid
}
case "$1" in
    is-active) [ -f /service.pid ] && kill -0 "$(cat /service.pid)" 2>/dev/null ;;
    start) [ -n "${FAKE_START_FAILS:-}" ] || start ;;
    stop) stop ;;
    restart) stop; start ;;
    *) ;;
esac
SCRIPT
chmod +x /test-bin/systemctl
export PATH="/test-bin:$PATH"

wait_for_status() {
    attempt=0
    until hermes-gadget-device status > /status.json 2>/dev/null; do
        attempt=$((attempt + 1))
        [ "$attempt" -lt 100 ]
        sleep 0.1
    done
}
set_release_id() {
    python3 -c '
import json, sys
p = json.load(open("package.json"))
p["release_id"] = p["version"] + "-" + sys.argv[1]
open("package.json", "w").write(json.dumps(p))
' "$1"
    find . -type f ! -name SHA256SUMS -exec sha256sum {} + > /updated-checksums
    mv /updated-checksums SHA256SUMS
}
current() { basename "$(readlink -f /opt/hermes-gadget/current)"; }
previous() { basename "$(readlink -f /opt/hermes-gadget/previous)"; }

# 1. A first installation, then the service starts and answers.
sh install.sh
first=$(current)
cli=/opt/hermes-gadget/current/venv/bin/hermes-gadget
export HGSIM_LIBRARY=/opt/hermes-gadget/current/libhgsim.so
"$cli" --version
systemctl start hermes-gadget.service
wait_for_status
python3 -c 'import json; s=json.load(open("/status.json")); assert s["board"] == "linux" and s["name"] == "Pi Gadget"'
sha256sum /var/lib/hermes-gadget/device.json /etc/hermes-gadget/config.json > /saved-state.sha256

# 2. Reinstalling the same package while the service runs: stop, switch, start, and the
#    health check sees it answer. State and configuration survive.
sh install.sh
grep -q '^stop hermes-gadget.service$' /systemctl.log
grep -q '^start hermes-gadget.service$' /systemctl.log
sha256sum --check /saved-state.sha256
wait_for_status
test -f /opt/hermes-gadget/current/licenses/NOTICE
cmp THIRD_PARTY_NOTICES.md /opt/hermes-gadget/current/licenses/THIRD_PARTY_NOTICES.md
test -f /opt/hermes-gadget/current/licenses/LICENSES/Apache-2.0.txt

# 3. A second release ID: the first becomes `previous`, the service runs on the new one.
set_release_id ffffffffffff
sh install.sh
sha256sum --check /saved-state.sha256
second=$(current)
test "$second" != "$first"
test "$(previous)" = "$first"
test -f /opt/hermes-gadget/previous/.installed
wait_for_status

# 4. Rollback swaps the two and restarts; a second rollback swaps back.
hermes-gadget-device rollback
test "$(current)" = "$first"
test "$(previous)" = "$second"
wait_for_status
hermes-gadget-device rollback
test "$(current)" = "$second"
test "$(previous)" = "$first"
wait_for_status

# 5. A release whose dependency installation fails leaves nothing behind and changes nothing.
set_release_id eeeeeeeeeeee
if PIP_NO_INDEX=1 sh install.sh; then
    echo 'the installer should have failed without a package index' >&2
    exit 1
fi
test ! -e "/opt/hermes-gadget/releases/$(python3 -c 'import json; print(json.load(open("package.json"))["release_id"])')"
test "$(current)" = "$second"
wait_for_status

# 6. A release that does not come up: the installer goes back to the previous one.
set_release_id bbbbbbbbbbbb
if FAKE_START_FAILS=1 HERMES_GADGET_HEALTH_TIMEOUT=3 sh install.sh; then
    echo 'the installer should have reported the failed health check' >&2
    exit 1
fi
test "$(current)" = "$second"
grep -q '^restart hermes-gadget.service$' /systemctl.log
wait_for_status

# 7. Old releases are pruned: the newest three stay, plus current and previous.
set_release_id cccccccccccc
sh install.sh
set_release_id dddddddddddd
sh install.sh
wait_for_status
test "$(current)" = "$(python3 -c 'import json; print(json.load(open("package.json"))["release_id"])')"
test ! -e "/opt/hermes-gadget/releases/$first"
test -e "/opt/hermes-gadget/releases/$second" || test -e "/opt/hermes-gadget/releases/$(previous)"
count=$(find /opt/hermes-gadget/releases -mindepth 1 -maxdepth 1 | wc -l)
[ "$count" -le 4 ]
sha256sum --check /saved-state.sha256
systemctl stop hermes-gadget.service
echo 'Install, service startup, reinstall, release switching, rollback, failed installs and pruning passed.'
