# Keep voice calls independent across devices

The phone must be able to make an independent voice call while a desktop call is active. Refusing the phone call or taking over the desktop call does not meet the requirement. The current Live Voice plugin's shared cached thread and global call state must therefore be replaced or bypassed with call isolation: starting, interrupting, or stopping one device's call must not affect another device's call.

Each phone call starts with fresh voice context. Spoken conversation context is not carried across calls; persistent Hermes task history remains in the gadget conversation. Fresh context and isolation must not depend on which profile, language, or model another device happens to use.

This increases the backend scope beyond an Android client for the current single-call broker. It does not establish that the subscription service supports simultaneous calls; that requires separate verification. Phone tasks use the phone's existing gadget conversation within its selected profile, as recorded in [the task identity decision](0003-reuse-the-gadget-conversation-for-voice-tasks.md). Task-result routing remains undecided.
