# Run the phone's call broker in the gateway

Issue #4 settles the bridge that [ADR 0004](0004-reuse-gadget-pairing-for-phone-voice-access.md) left open. The phone sends its WebRTC offer over its paired gadget connection, and the gadget plugin in the Hermes gateway process starts the call. The gateway loads the Hermes Live Voice plugin's broker module from that plugin's install directory and runs its own broker instance, with its own `codex app-server`, on the host's Codex login. The broker's code is shared, but no call or object state is shared with the dashboard's broker. A small public API in the plugin (`start_call`, `stop_call`) is the boundary. The gadget plugin doesn't call the plugin's private names.

Calling the dashboard's `/codexlive` routes from the gateway would need a dashboard credential. Hermes's dashboard gate admits only a login session or bearer token from its auth providers. The legacy session token applies only in loopback mode. Any working option would mean storing a dashboard credential on the host for the gateway, or patching Hermes's dashboard authentication. Running the broker in the gateway needs neither. The dashboard keeps its access control unchanged, and no credential reaches the phone.

Separate app-server processes also strengthen [ADR 0002](0002-independent-device-voice-calls.md): a desktop call and a phone call share no broker state. The gadget hub owns per-device call state. It enforces one call per device, reads current pairing on every start and checks it again before sending the answer, ends a call on disconnect or unpairing, and hangs up answers that arrive after their call ended. The call runs in the gadget platform's profile, and fields the device sends about identity or profile are ignored.

Consequences:

- Phone calls are opt-in (`live_calls`), because they spend the subscription's voice allowance.
- The gateway process imports the Live Voice plugin's module, which adds that plugin's directories to `sys.path`. A Live Voice version without `start_call` turns calls off with a logged reason.
- The gateway and dashboard each run a `codex app-server` while they have calls. Whether the subscription service accepts overlapping calls is a live measurement, not a property of this design.
