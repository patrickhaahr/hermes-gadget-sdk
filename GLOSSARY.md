# Android Hermes voice

Language for hands-free Hermes conversations on Android, initially tested on a dedicated OnePlus 8T and later available in an ordinary phone app.

## Language

**Wake listening**:
The state in which the phone listens locally for the wake phrase before a wake request or voice call begins. Microphone audio stays on the phone while it is waiting for the phrase.

**Voice call**:
A spoken conversation activated by local wake detection or an explicit Start call action, through which the user can converse and request Hermes tasks. Each call starts with fresh spoken context and is independent of calls on other devices.
_Avoid_: Gadget turn, Hermes session

**Hang-up**:
Ending the voice call while leaving tasks already submitted to Hermes running. A completed task does not make the phone speak after hang-up.
_Avoid_: Cancel task, reset conversation

**Ready cue**:
The audible signal that the voice call is ready for the user to speak. The user activates the call, then waits for this cue before speaking their request.

**Hermes task**:
A request handed from a voice conversation to Hermes for execution and a result. It is distinct from the voice conversation itself.

**Gadget conversation**:
The persistent Hermes conversation belonging to a paired device within its selected profile. The phone's voice calls hand tasks to this conversation, whose history survives hang-up.
_Avoid_: Voice call

**Dedicated mode**:
Use of a phone as a continuously available Hermes device in kiosk mode. The initial test device is the OnePlus 8T; ordinary app use is a later target.

**Voice mode**:
The saved choice of what wake detection activates: Hermes voice or Live voice. Hermes voice is the default; Live voice is unavailable until its call integration lands.

**Hermes voice**:
One wake request through Hermes's existing speech recognition, gadget conversation and text-to-speech. It uses whichever STT/TTS the host has configured and does not open a Live voice call.

**Wake request**:
A single locally buffered recording starting with the detection chunk, ending after speech and a pause or the maximum length. Earlier audio never leaves the phone. Silence and cancelled recordings are discarded without upload. Another request requires another wake.
