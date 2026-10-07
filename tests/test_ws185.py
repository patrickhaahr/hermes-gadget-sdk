"""The 1.85C V2 simulator profile: round corners stay dark, swipe down cancels, a tap answers yes."""
import json

from conftest import requires_sim


@requires_sim
def test_v2_round_profile_tap_yes_swipe_cancel_and_boot_talk(devserver, make_sim):
    _hub, _brain, url = devserver()
    sim = make_sim(url, board="sim-360x360-round")
    assert sim.wait_screen("ready", timeout=10)
    # Circular glass hides the rectangular corners.
    assert sim.rgb888()[:3] == bytes(3)
    sim.press("talk")
    assert sim.wait_screen("listening", timeout=2)
    sim.touch(True, 180, 80)
    sim.touch(True, 180, 220)
    sim.touch(False)
    assert sim.wait_screen("ready", timeout=2)
    sim.release("talk")
    sim.device.transport_text(json.dumps({"type": "prompt", "id": "v2-q", "text": "Continue?"}))
    assert sim.device.screen() == "prompt"
    sim.run_for(0.7)
    sim.touch(True, 180, 180)
    sim.run_for(0.05)
    sim.touch(False)
    assert sim.wait_for(lambda: any(m.get("type") == "prompt.reply" and m.get("id") == "v2-q"
                                  and m.get("answer") == "yes" for m in sim.sent), timeout=2)
    assert sim.device.screen() != "listening"
