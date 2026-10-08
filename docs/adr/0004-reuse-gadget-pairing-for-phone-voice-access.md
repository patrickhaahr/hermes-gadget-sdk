# Reuse gadget pairing for phone voice access

The phone uses its existing gadget pairing for voice access and its paired connection for Hermes task handoffs, rather than adding a dashboard login. This preserves the established device and profile identity and avoids duplicating phone authentication and task-session routing. It requires additive protocol support; the current Gadget socket has no voice-call negotiation handlers.

The enrolled device identity and current pairing authorization must both be checked. Existing dashboard authentication remains in place: a Gadget key is not automatically a dashboard credential. The implementation must bridge the authenticated phone to an isolated subscription call broker without storing a dashboard administrator password on the phone or relying on process-local gateway objects being available in a separate dashboard process.
