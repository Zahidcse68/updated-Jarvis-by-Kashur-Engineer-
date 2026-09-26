"""
Comprehensive test suite for MARK LIV components.
Tests module loading, action registry, avatar geometry, memory management, and audio device detection.
"""
import sys
import os
import traceback

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

for _s in (getattr(sys, "stdout", None), getattr(sys, "stderr", None)):
    if _s is not None and hasattr(_s, "reconfigure"):
        try:
            _s.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

results = []

def run_test(name, test_func):
    try:
        test_func()
        print(f"  [PASS] {name}")
        results.append((name, True, None))
    except Exception as e:
        print(f"  [FAIL] {name}: {e}")
        traceback.print_exc()
        results.append((name, False, str(e)))

print("=" * 60)
print("  MARK LIV COMPONENT TEST SUITE")
print("=" * 60)

# 1. Config Manager Test
def test_config_manager():
    from memory import config_manager
    name = config_manager.get_assistant_name()
    assert name, "Assistant name should not be empty"
    voice = config_manager.get_voice()
    assert voice in config_manager.AVAILABLE_VOICES, f"Unexpected voice: {voice}"
    user = config_manager.get_user_name()
    print(f"    (Assistant: {name}, Default Voice: {voice})")

run_test("Config Manager", test_config_manager)

# 2. Memory Manager Test
def test_memory_manager():
    from memory import memory_manager
    mem = memory_manager.load_memory()
    assert isinstance(mem, dict), "Memory should be a dict"
    prompt_str = memory_manager.format_memory_for_prompt(mem)
    assert isinstance(prompt_str, str), "Prompt should be a string"
    print(f"    (Loaded memory keys: {list(mem.keys())})")

run_test("Memory Manager", test_memory_manager)

# 3. Avatar Mesh & Visemes Test
def test_avatar():
    from core import viseme
    # Verify text_to_visemes works
    seq = viseme.text_to_visemes("Hello Jarvis")
    assert len(seq) > 0, "Viseme sequence is empty"
    print(f"    (Generated {len(seq)} viseme frames for 'Hello Jarvis')")

run_test("Avatar & Viseme Engine", test_avatar)

# 4. Audio Devices Test
def test_audio_devices():
    import sounddevice as sd
    devices = sd.query_devices()
    print(f"    (Total audio hardware endpoints detected: {len(devices)})")

run_test("Audio Device Detection", test_audio_devices)

# 5. Action Loader & Discovery Test
def test_action_loader():
    from pathlib import Path
    from core import action_loader
    registry = action_loader.discover_actions(Path("actions"))
    actions = registry.names()
    print(f"    (Auto-discovered actions: {len(actions)}) -> {sorted(list(actions))[:5]}...")
    assert len(actions) > 0, "Should discover at least 1 action tool"

run_test("Action Loader & Discovery", test_action_loader)

# 6. Plugin Loader Test
def test_plugin_loader():
    from pathlib import Path
    from core import plugin_loader
    registry = plugin_loader.discover_plugins(Path("plugins"), core_tool_names=set())
    decls = registry.get_tool_declarations()
    print(f"    (Auto-discovered plugins: {len(decls)})")

run_test("Plugin Loader", test_plugin_loader)

# 7. Action Modules Import Test
def test_action_modules():
    import actions.computer_control
    import actions.web_search
    import actions.file_processor
    import actions.code_helper
    import actions.system_monitor
    import actions.weather_report
    import actions.youtube_video
    import actions.screen_processor
    import actions.desktop

run_test("Action Modules Import", test_action_modules)

# 8. Web Search Engine Test
def test_web_search():
    from actions.web_search import _ddg_search
    res = _ddg_search("Python Gemini")
    assert isinstance(res, list), f"Expected list results, got {type(res)}"
    print(f"    (DuckDuckGo search operational, got {len(res)} results)")

run_test("Web Search Engine", test_web_search)

# 9. System Monitor Test
def test_system_monitor():
    from actions.system_monitor import SystemMonitor
    monitor = SystemMonitor()
    alert = monitor.check()
    print("    (System telemetry monitor operational)")

run_test("System Telemetry Monitor", test_system_monitor)

print("=" * 60)
passed = sum(1 for _, s, _ in results if s)
failed = sum(1 for _, s, _ in results if not s)
print(f"TEST RESULTS: {passed} PASSED, {failed} FAILED (Total: {len(results)})")
print("=" * 60)
