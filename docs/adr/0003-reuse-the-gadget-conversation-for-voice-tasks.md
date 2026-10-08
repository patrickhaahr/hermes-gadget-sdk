# Reuse the gadget conversation for voice tasks

Tasks delegated from the phone's voice calls use its existing gadget conversation within the selected Hermes profile. Creating a separate voice-only conversation or a fresh task conversation on every call would split the phone's history. Reusing the gadget conversation preserves continuity between ordinary gadget interactions and voice tasks, while desktop tasks keep their own conversation.

A voice call and a gadget conversation have separate lifetimes: hanging up does not reset the gadget conversation or cancel any task already submitted to Hermes. Such tasks finish in the gadget conversation. After hang-up their completion may be shown silently, but the phone does not speak it or start a new call. Requests still waiting locally are not submitted after hang-up.

The next voice call starts with fresh spoken context while retaining the gadget conversation's task history. Existing Hermes approval policy remains authoritative, including the user's auto-approval configuration. When Hermes does ask for confirmation, retain explicit on-screen approval and leave the call active; the initial integration does not add spoken approvals or a new approval system.

The initial voice integration admits one active Hermes task at a time. While it is running, another task request receives explicit wait feedback rather than being queued or silently replacing a request. Ordinary voice conversation can continue while Hermes works. Hanging up never cancels a submitted task, including when the microphone is explicitly turned off.
