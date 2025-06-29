import websocket
import threading
import time
import json
import os
import random
import sys
import win32api
import win32con
import traceback

from pynput import keyboard as pynput_keyboard
from pynput import mouse as pynput_mouse
import math
from collections import deque

def debug(msg, always=False):
    if always or "--debug" in sys.argv:
        print(f"[DEBUG {time.strftime('%H:%M:%S')}] {msg}")

debug("Run As Administrator For 100% Functionality", always=True)

def load_json_or_exit(path, name):
    if not os.path.isfile(path):
        print(f"ERROR: {name} not found at {path}")
        sys.exit(1)
    with open(path, "r") as f:
        return json.load(f)

ANIMATION_PRAYER_MAPPING_PATH = os.environ.get("ANIMATION_PRAYER_MAPPING", r"C:\Users\Admin\.runelite\animation_prayer_mapping.json")
WEAPON_SPEED_MAPPING_PATH = os.environ.get("WEAPON_SPEED_MAPPING", r"C:\Users\Admin\.runelite\weapon_speed_mapping.json")
MANUAL_COORDS_PATH = os.environ.get("COORDS_JSON", r"C:\Users\Admin\.runelite\manual_coords_mapping.json")
SWITCHER_HOTKEYS_PATH = os.environ.get("HOTKEYS_JSON", r"C:\Users\Admin\.runelite\switcher_hotkeys.json")

prayer_data = load_json_or_exit(ANIMATION_PRAYER_MAPPING_PATH, "animation_prayer_mapping.json")
ANIMATION_TO_PRAYER = {str(entry["animation_id"]): entry["attack_type"] for entry in prayer_data} if isinstance(prayer_data, list) else prayer_data

tickrate_data = load_json_or_exit(WEAPON_SPEED_MAPPING_PATH, "weapon_speed_mapping.json")
ANIMATION_TO_TICKRATE = {str(entry["animation_id"]): entry["tickrate"] for entry in tickrate_data} if isinstance(tickrate_data, list) else tickrate_data

COORDS_MAP = load_json_or_exit(MANUAL_COORDS_PATH, "manual_coords_mapping.json")
SWITCHER_HOTKEYS = load_json_or_exit(SWITCHER_HOTKEYS_PATH, "switcher_hotkeys.json")

try:
    import psutil
    psutil.Process(os.getpid()).nice(psutil.HIGH_PRIORITY_CLASS)
except Exception:
    pass

# ------------------ CONSTANTS ------------------
OFFSET_MIN, OFFSET_MAX = -4, 4
BEZIER_STEPS, BEZIER_JITTER = 24, 6
BUSY_WAIT_THRESHOLD = 0.0008

TARGET_LEFT, TARGET_TOP, TARGET_WIDTH, TARGET_HEIGHT = -1754, 183, 1377, 953

STANDARD_MELEE_RANGE, HALBERD_MELEE_RANGE = 1, 2
HALBERD_WEAPON_IDS = {3190, 3192, 3194, 3196, 20431, 25985}

BRACKET_MAP = {27166: "pure", 27169: "zerker", 23591: "max"}
PRAYER_LOGIC = {
    "piety": {"max": ["piety"], "zerker": ["ultimate_strength", "incredible_reflexes"], "pure": ["ultimate_strength", "incredible_reflexes"]},
    "rigour": {"max": ["rigour"], "zerker": ["eagle_eye"], "pure": ["eagle_eye"]},
    "augury": {"max": ["augury"], "zerker": ["mystic_might"], "pure": ["mystic_might"]}
}
F10_SPEC_WEAPON_IDS = {27184, 20593, 20784}
DEFENDER_IDS = {23597, 27185}
DARK_BOW_ID, DRAGON_ARROW_ID, ELDER_MAUL_ID = 20408, 20389, 21205
DHAROKS_GREATAXE_ID, VERACS_FLAIL_ID, GMAUL_ID = 25516, 27189, 20557
MAGES_BOOK_ID, SPIRIT_SHIELD_ID, UNHOLY_BOOK_ID = 6889, 23599, 27191

# --- Protection Prayer Timing ---
last_switch_tick = 0
last_switch_prayer = None
last_emergency_switch_time = 0  # timestamp in seconds
last_animation_id = None
animation_cooldown_until_tick = 0
last_recommended_prayer = None
recommended_cooldown_until_tick = 0
last_prayer_switch_time = 0
last_prayer_switch_name = None
PRAYER_SWITCH_COOLDOWN = 0.66  # seconds; 1 tick = 0.6s, slightly more to ensure new state

# ------------------ RANDOMIZED DELAYS ------------------
def rand_inv_click_delay(): return random.uniform(0.021, 0.037)
def rand_prayer_click_delay(): return random.uniform(0.019, 0.032)
def rand_tab_switch_delay(): return random.uniform(0.021, 0.044)
def rand_mouse_restore_delay(distance=0): return random.uniform(0.014, 0.030)
def rand_gear_sleep(): return 0.012 + random.uniform(0.005, 0.019)
def rand_mouse_move_duration(): return random.uniform(0.013, 0.026)
def rand_spec_bar_wait(): return random.uniform(0.024, 0.038)
def rand_spec_bar_preclick(): return random.uniform(0.016, 0.032)
def rand_double_click_gap(): return random.uniform(0.037, 0.082)

def random_offset(): return random.randint(OFFSET_MIN, OFFSET_MAX)
def antiban_offset(amount=5): return (random.randint(-amount, amount), random.randint(-amount, amount))

def bring_runelite_to_front_and_position():
    try:
        import pygetwindow
        for w in pygetwindow.getAllWindows():
            if w.title.startswith("RuneLite -") and "File Explorer" not in w.title and w.title.strip():
                w.restore()
                w.moveTo(TARGET_LEFT, TARGET_TOP)
                w.resizeTo(TARGET_WIDTH, TARGET_HEIGHT)
                w.activate()
                time.sleep(0.008)
                break
    except Exception:
        pass

# ------------------ GAME STATE ------------------
class GameState:
    def __init__(self):
        self.lock = threading.Lock()
        self.tick = 0
        self.tick_timestamp = 0
        self.inventory = {}
        self.equipment = {}
        self.tab = None
        self.widget = {}
        self.spec_bar = None
        self.prayers = []
        self.opponent = None
        self.canvas_width = 765
        self.canvas_height = 503
        self.recommended_protection_prayer = None
        self.player_world_position = None

    def update(self, ws_event):
        global locked_bracket
        typ, data = ws_event.get("type"), ws_event.get("data")
        bracket_changed = False
        with self.lock:
            if typ == "tick":
                self.tick = data.get("tick", self.tick)
                self.tick_timestamp = data.get("tick_timestamp", self.tick_timestamp)
            elif typ == "tick_state":
                self.tick = data.get("tick", self.tick)
                self.tick_timestamp = data.get("tick_timestamp", self.tick_timestamp)
                self.inventory = data.get("inventory_slots", self.inventory)
                self.equipment = data.get("equipment_slots", self.equipment)
                self.tab = data.get("tab", self.tab)
                self.prayers = data.get("prayers", self.prayers)
                self.opponent = data.get("opponent", self.opponent)
                self.spec_bar = data.get("spec_bar", self.spec_bar)
                self.widget = data.get("spells", self.widget)
                self.canvas_width = data.get("canvas_width", self.canvas_width)
                self.canvas_height = data.get("canvas_height", self.canvas_height)
                self.recommended_protection_prayer = data.get("recommended_protection_prayer", None)
                self.player_world_position = data.get("player_world_position", self.player_world_position)
                bracket_changed = True
            elif typ == "inventory_changed":
                self.inventory = data.get("items", self.inventory)
            elif typ == "equipment_changed":
                self.equipment = data.get("items", self.equipment)
                bracket_changed = True
            elif typ == "tab_changed":
                self.tab = data.get("tab", self.tab)
            elif typ == "spec_bar":
                self.spec_bar = data
            elif typ == "prayers":
                self.prayers = data
            elif typ == "opponent":
                self.opponent = data
            elif typ == "recommended_protection_prayer":
                self.recommended_protection_prayer = data.get("recommended_prayer") if isinstance(data, dict) else data
        if bracket_changed:
            slot0 = self.equipment.get("0", {}).get("item_id", -1)
            with locked_bracket_lock:
                locked_bracket = BRACKET_MAP.get(slot0, "max") if slot0 != -1 else None

    def get(self):
        with self.lock:
            return {
                "tick": self.tick,
                "tick_timestamp": self.tick_timestamp,
                "inventory": self.inventory.copy(),
                "equipment": self.equipment.copy(),
                "tab": self.tab,
                "widget": self.widget.copy(),
                "spec_bar": self.spec_bar,
                "prayers": list(self.prayers),
                "opponent": self.opponent,
                "canvas_width": self.canvas_width,
                "canvas_height": self.canvas_height,
                "recommended_protection_prayer": self.recommended_protection_prayer,
                "player_world_position": self.player_world_position
            }

game_state = GameState()

locked_bracket, last_f11_time = None, 0
locked_bracket_lock, last_f11_time_lock = threading.Lock(), threading.Lock()
action_lock, action_queue, queue_event = threading.Lock(), deque(), threading.Event()
auto_protect_enabled, auto_protect_lock = False, threading.Lock()
hotkey_sequence_active, hotkey_sequence_lock = False, threading.Lock()
prayer_tab_protection_active, prayer_tab_protection_lock = False, threading.Lock()
# --- Mouse/Movement/Click helpers ---
def bezier_curve(p0, p1, p2, p3, t):
    omt = 1 - t
    omt2 = omt * omt
    omt3 = omt2 * omt
    t2 = t * t
    t3 = t2 * t
    return (
        omt3 * p0[0] + 3 * omt2 * t * p1[0] + 3 * omt * t2 * p2[0] + t3 * p3[0],
        omt3 * p0[1] + 3 * omt2 * t * p1[1] + 3 * omt * t2 * p2[1] + t3 * p3[1],
    )

def move_mouse_bezier(x, y, total_duration=None, steps=BEZIER_STEPS, jitter=BEZIER_JITTER, stop_event=None, context=""):
    if total_duration is None:
        total_duration = rand_mouse_move_duration()
    start = win32api.GetCursorPos()
    p0 = start
    p3 = (x, y)
    mx = (p0[0] + p3[0]) // 2
    my = (p0[1] + p3[1]) // 2
    ctrl_variation = random.randint(10, 80)
    angle = random.uniform(0, 2 * math.pi)
    offset1 = (
        int(ctrl_variation * random.uniform(0.3, 1.0) * math.cos(angle)),
        int(ctrl_variation * random.uniform(0.3, 1.0) * math.sin(angle))
    )
    angle2 = angle + random.uniform(-0.8, 0.8)
    offset2 = (
        int(ctrl_variation * random.uniform(0.3, 1.0) * math.cos(angle2)),
        int(ctrl_variation * random.uniform(0.3, 1.0) * math.sin(angle2))
    )
    p1 = (mx + offset1[0], my + offset1[1])
    p2 = (mx + offset2[0], my + offset2[1])
    sleep_per = total_duration / steps if steps else 0
    t0 = time.perf_counter()
    for i in range(1, steps + 1):
        if stop_event and stop_event.is_set():
            debug(f"move_mouse_bezier interrupted {context} at step {i}/{steps}", always=True)
            return
        t = i / steps
        px, py = bezier_curve(p0, p1, p2, p3, t)
        px += random.randint(-jitter, jitter)
        py += random.randint(-jitter, jitter)
        win32api.SetCursorPos((int(px), int(py)))
        target = t0 + sleep_per * i
        remain = target - time.perf_counter()
        if remain > 0:
            if remain < BUSY_WAIT_THRESHOLD:
                time.sleep(0.0005)
            else:
                time.sleep(remain / 2)
    win32api.SetCursorPos((x, y))

def fast_click(stop_event=None, context=""):
    if stop_event and stop_event.is_set():
        debug(f"fast_click interrupted {context}", always=True)
        return
    win32api.mouse_event(win32con.MOUSEEVENTF_LEFTDOWN, 0, 0)
    win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0)

def fast_move_and_click(x, y, delay=None, stop_event=None, jitter=BEZIER_JITTER, context=""):
    if stop_event and stop_event.is_set():
        debug(f"fast_move_and_click interrupted before move {context}", always=True)
        return
    if delay is None:
        delay = rand_mouse_move_duration()
    rx = x + random_offset()
    ry = y + random_offset()
    move_mouse_bezier(rx, ry, total_duration=delay, steps=BEZIER_STEPS, jitter=jitter, stop_event=stop_event, context=context)
    if stop_event and stop_event.is_set():
        debug(f"fast_move_and_click interrupted before click {context}", always=True)
        return
    fast_click(stop_event=stop_event, context=context)

def switch_tab(tab_name, stop_event=None):
    if stop_event and stop_event.is_set():
        debug(f"switch_tab interrupted before tab {tab_name}", always=True)
        return
    tab_hotkeys = {"inventory": "a", "prayer": "s", "spellbook": "d", "attack": "q"}
    key = tab_hotkeys.get(tab_name)
    if key:
        import keyboard as kb
        kb.press_and_release(key)
        cur = win32api.GetCursorPos()
        move_mouse_bezier(cur[0], cur[1], total_duration=rand_tab_switch_delay(), steps=2, jitter=1, stop_event=stop_event, context=f"switch_tab {tab_name}")

def ultra_accurate_sleep(secs, stop_event=None, context=""):
    target = time.perf_counter() + secs
    while True:
        if stop_event and stop_event.is_set():
            debug(f"ultra_accurate_sleep interrupted {context}", always=True)
            return
        remain = target - time.perf_counter()
        if remain <= 0:
            break
        if remain < BUSY_WAIT_THRESHOLD:
            time.sleep(0.0005)
            continue
        time.sleep(remain / 2)

def get_protect_prayer_coords(prayer_name):
    try:
        coords = COORDS_MAP["prayers"].get(prayer_name)
        if coords and "x" in coords and "y" in coords:
            return coords["x"], coords["y"]
    except Exception:
        pass
    return None

# ---- PLANNED PROTECTION & STATE LOGIC ----
_planned_protection_lock = threading.Lock()
_planned_protection_prayer = None

def set_planned_protection_prayer(prayer):
    global _planned_protection_prayer
    with _planned_protection_lock:
        _planned_protection_prayer = prayer

def get_planned_protection_prayer():
    with _planned_protection_lock:
        return _planned_protection_prayer

def is_action_locked(): return action_lock.locked()
def is_prayer_tab_protected(): return prayer_tab_protection_active
def is_auto_protect_enabled(): return auto_protect_enabled
def is_hotkey_sequence_active(): return hotkey_sequence_active

def set_hotkey_sequence_active(active):
    global hotkey_sequence_active
    with hotkey_sequence_lock:
        hotkey_sequence_active = active

def set_prayer_tab_protection(active):
    global prayer_tab_protection_active
    with prayer_tab_protection_lock:
        prayer_tab_protection_active = active
        debug(f"[Click Block] Prayer tab protection {'ENABLED' if active else 'DISABLED'}", always=True)

def should_block_prayer_clicks():
    return is_auto_protect_enabled() or is_prayer_tab_protected()

def prayer_active(prayer_name, prayers):
    query = str(prayer_name).lower().strip()
    return any(str(p.get("name", "")).lower().strip() == query for p in prayers)

def get_current_protection_prayer():
    state = game_state.get()
    prayers = state["prayers"]
    for prot in ["protect_from_magic", "protect_from_missiles", "protect_from_melee"]:
        if prayer_active(prot, prayers):
            return prot
    return None

def get_bracket(equipment):
    global locked_bracket
    slot0 = equipment.get("0", {}).get("item_id", -1)
    with locked_bracket_lock:
        if slot0 == -1:
            return None
        if locked_bracket is None:
            locked_bracket = BRACKET_MAP.get(slot0, "max")
        return locked_bracket

def queue_script_action(func):
    action_queue.append(func)
    queue_event.set()

def action_queue_thread():
    while True:
        queue_event.wait()
        while action_queue:
            func = action_queue.popleft()
            with action_lock:
                try:
                    func()
                except Exception as e:
                    debug(f"[Action Queue] Exception in queued action: {e}\n{traceback.format_exc()}", always=True)
        queue_event.clear()

# ---- Prayer/Gear/Spell switching logic ----
def switch_gear(wanted_items, inventory, equipment, stop_event=None, skip_defender=False):
    equipped_ids = {v['item_id'] for v in equipment.values()}
    items_to_equip = []
    for item in wanted_items:
        item_id = item['id']
        if skip_defender and item_id in DEFENDER_IDS:
            continue
        if item_id not in equipped_ids:
            items_to_equip.append(item)
    if not items_to_equip:
        debug("All required gear already equipped, not switching tab.", always=True)
        return

    switch_tab("inventory", stop_event=stop_event)
    weapon_item = None
    non_weapon_items = []
    for item in items_to_equip:
        if item.get("slot", None) == 3:
            weapon_item = item
        else:
            non_weapon_items.append(item)
    if weapon_item is None and len(items_to_equip) > 0:
        weapon_item = items_to_equip[-1]
        non_weapon_items = items_to_equip[:-1]

    state = game_state.get()
    current_tab = state.get("tab")

    def click_inventory_slot(x, y, context=""):
        if (current_tab or "").lower() == "inventory":
            lshift_was_down = win32api.GetKeyState(win32con.VK_LSHIFT) < 0
            rshift_was_down = win32api.GetKeyState(win32con.VK_RSHIFT) < 0
            released_lshift, released_rshift = False, False
            try:
                if lshift_was_down:
                    win32api.keybd_event(win32con.VK_LSHIFT, 0, win32con.KEYEVENTF_KEYUP, 0)
                    released_lshift = True
                if rshift_was_down:
                    win32api.keybd_event(win32con.VK_RSHIFT, 0, win32con.KEYEVENTF_KEYUP, 0)
                    released_rshift = True
                ultra_accurate_sleep(rand_gear_sleep(), stop_event=stop_event)
                fast_move_and_click(x, y, delay=rand_inv_click_delay(), stop_event=stop_event, context=context)
            finally:
                if released_lshift and win32api.GetKeyState(win32con.VK_LSHIFT) >= 0:
                    win32api.keybd_event(win32con.VK_LSHIFT, 0, 0, 0)
                    ultra_accurate_sleep(0.004 + random.uniform(0, 0.004))
                if released_rshift and win32api.GetKeyState(win32con.VK_RSHIFT) >= 0:
                    win32api.keybd_event(win32con.VK_RSHIFT, 0, 0, 0)
                    ultra_accurate_sleep(0.004 + random.uniform(0, 0.004))
        else:
            ultra_accurate_sleep(rand_gear_sleep(), stop_event=stop_event)
            fast_move_and_click(x, y, delay=rand_inv_click_delay(), stop_event=stop_event, context=context)

    for item in non_weapon_items:
        if stop_event and stop_event.is_set():
            debug("switch_gear interrupted", always=True)
            return
        item_id = item['id']
        for slot_str, item_info in inventory.items():
            if item_info['item_id'] == item_id:
                coords = COORDS_MAP["inventory"].get(slot_str)
                if coords:
                    click_inventory_slot(coords['x'], coords['y'], context=f"gear {item_id}")
                break
    if weapon_item and not (stop_event and stop_event.is_set()):
        item_id = weapon_item['id']
        for slot_str, item_info in inventory.items():
            if item_info['item_id'] == item_id:
                coords = COORDS_MAP["inventory"].get(slot_str)
                if coords:
                    click_inventory_slot(coords['x'], coords['y'], context=f"gear {item_id} (weapon-last)")
                break

def switch_prayers(prayer_names, prayers, stop_event=None, protection_prayer=None):
    if prayer_names is None:
        prayer_names = []
    prayers_to_activate = [pr for pr in prayer_names if not prayer_active(pr, prayers)]
    allowed_prayers = {"protect_from_magic", "protect_from_missiles", "protect_from_melee", "augury", "rigour"}
    need_protection_switch = False
    if protection_prayer and protection_prayer in allowed_prayers:
        current_protection = get_current_protection_prayer()
        if protection_prayer != current_protection:
            need_protection_switch = True
            debug(f"[Prayer Switch] Protection switch needed: '{current_protection}' -> '{protection_prayer}'", always=True)
        else:
            debug(f"[Prayer Switch] Protection prayer '{protection_prayer}' already active (state), clicking anyway to ensure!", always=True)
            need_protection_switch = True
    else:
        protection_prayer = None

    if not prayers_to_activate and not need_protection_switch:
        debug("[Prayer Switch] All prayers already correct, skipping entirely", always=True)
        return
    set_prayer_tab_protection(True)
    try:
        switch_tab("prayer", stop_event=stop_event)
        # Robust prayer switching: try up to 3 times for protection prayer!
        if need_protection_switch:
            coords = get_protect_prayer_coords(protection_prayer)
            if coords:
                for attempt in range(3):
                    ultra_accurate_sleep(rand_gear_sleep(), stop_event=stop_event)
                    x = coords[0] + random_offset()
                    y = coords[1] + random_offset()
                    fast_move_and_click(x, y, delay=rand_prayer_click_delay(), stop_event=stop_event, context=f"PRIORITY_protection_{protection_prayer}_attempt{attempt+1}")
                    ultra_accurate_sleep(0.050 + random.uniform(0.020, 0.030), stop_event=stop_event)
                    state_now = game_state.get()
                    if prayer_active(protection_prayer, state_now["prayers"]):
                        debug(f"[Prayer Switch] Confirmed {protection_prayer} is now active after {attempt+1} click(s)", always=True)
                        break
                    else:
                        debug(f"[Prayer Switch] {protection_prayer} NOT enabled after click, retrying (attempt {attempt+2})", always=True)
                else:
                    debug(f"[Prayer Switch] {protection_prayer} FAILED to enable after 3 tries", always=True)
        for pr in prayers_to_activate:
            if stop_event and stop_event.is_set():
                debug("switch_prayers interrupted", always=True)
                return
            coords = COORDS_MAP["prayers"].get(pr)
            if coords:
                ultra_accurate_sleep(rand_gear_sleep(), stop_event=stop_event)
                fast_move_and_click(coords['x'], coords['y'], delay=rand_prayer_click_delay(), stop_event=stop_event, context=f"offensive_{pr}")
                debug(f"[Prayer Switch] Activated offensive prayer: {pr}", always=True)
        switch_tab("inventory", stop_event=stop_event)
    finally:
        set_prayer_tab_protection(False)

def standalone_protection_switch(protection_prayer, restore_mouse_pos=True):
    orig_mouse = win32api.GetCursorPos() if restore_mouse_pos else None
    try:
        state = game_state.get()
        prayers = state["prayers"]
        switch_prayers([], prayers, protection_prayer=protection_prayer)
        if restore_mouse_pos and orig_mouse:
            offx, offy = antiban_offset(4)
            move_mouse_bezier(
                orig_mouse[0] + offx, orig_mouse[1] + offy,
                total_duration=rand_mouse_restore_delay(), steps=10, jitter=3
            )
            debug(f"[Standalone] Mouse restored to {orig_mouse[0] + offx}, {orig_mouse[1] + offy}", always=True)
    except Exception as e:
        debug(f"[Standalone] Error in protection switch: {e}", always=True)

def cast_spell(spell_name, widget_state, stop_event=None):
    switch_tab("spellbook", stop_event=stop_event)
    coords = COORDS_MAP["spells"].get(spell_name)
    if coords:
        ultra_accurate_sleep(0.013 + random.uniform(0.006, 0.019), stop_event=stop_event)
        fast_move_and_click(coords['x'], coords['y'], delay=rand_mouse_move_duration(), stop_event=stop_event, context=f"spell {spell_name}")

def click_spec_bar(stop_event=None):
    bar = COORDS_MAP["spec_bar"]
    x = (bar["top_left"]["x"] + bar["top_right"]["x"] + bar["bottom_left"]["x"] + bar["bottom_right"]["x"]) // 4
    y = (bar["top_left"]["y"] + bar["top_right"]["y"] + bar["bottom_left"]["y"] + bar["bottom_right"]["y"]) // 4
    x += random.randint(-3, 3)
    y += random.randint(-3, 3)
    move_mouse_bezier(x, y, total_duration=rand_mouse_move_duration(), steps=BEZIER_STEPS // 2, jitter=BEZIER_JITTER)
    ultra_accurate_sleep(0.011 + random.uniform(0, 0.005))
    fast_click()

def run_switch(hotkey, stop_event):
    def do_switch():
        try:
            set_hotkey_sequence_active(True)
            orig_mouse = win32api.GetCursorPos()
            state = game_state.get()
            inventory, equipment, prayers, widget_state = state["inventory"], state["equipment"], state["prayers"], state["widget"]
            bracket = get_bracket(equipment)
            if bracket is None: return
            switch_cfg = SWITCHER_HOTKEYS.get(hotkey)
            wanted_items = switch_cfg.get("items", []).copy() if switch_cfg else []
            protection_prayer = get_recommended_protection_prayer()
            # AGS→Gmaul Spec Stack (F11)
            has_gmaul = any(slot.get("item_id") == GMAUL_ID for slot in inventory.values()) or any(slot.get("item_id") == GMAUL_ID for slot in equipment.values())
            if hotkey == "F11" and has_gmaul:
                prayers_for_bracket = PRAYER_LOGIC["piety"][bracket]
                switch_gear(wanted_items, inventory, equipment, stop_event=stop_event, skip_defender=False)
                if stop_event.is_set():
                    return
                if prayers_for_bracket or protection_prayer:
                    switch_prayers(prayers_for_bracket or [], prayers, stop_event=stop_event, protection_prayer=protection_prayer)
                    if stop_event.is_set():
                        return
                gmaul_slot = None
                for slot_str, item in inventory.items():
                    if item.get("item_id") == GMAUL_ID:
                        gmaul_slot = COORDS_MAP["inventory"].get(slot_str)
                        break
                if gmaul_slot:
                    ultra_accurate_sleep(0.024 + random.uniform(0, 0.014), stop_event=stop_event)
                    fast_move_and_click(
                        gmaul_slot['x'], gmaul_slot['y'],
                        delay=rand_mouse_move_duration(), stop_event=stop_event, context="f11_gmaul"
                    )
                ultra_accurate_sleep(0.021 + random.uniform(0, 0.013), stop_event=stop_event)
                switch_tab("attack", stop_event=stop_event)
                ultra_accurate_sleep(0.024 + random.uniform(0, 0.016), stop_event=stop_event)
                click_spec_bar(stop_event=stop_event)
                ultra_accurate_sleep(rand_double_click_gap(), stop_event=stop_event)
                click_spec_bar(stop_event=stop_event)
                ultra_accurate_sleep(0.022 + random.uniform(0, 0.011), stop_event=stop_event)
                switch_tab("inventory", stop_event=stop_event)
                offx, offy = antiban_offset(4)
                move_mouse_bezier(
                    orig_mouse[0] + offx, orig_mouse[1] + offy,
                    total_duration=rand_mouse_restore_delay(), steps=10, jitter=3
                )
                ultra_accurate_sleep(0.017 + random.uniform(0, 0.011))
                fast_click()
                return
            # Mage Switch (F6)
            if (hotkey == "F11" and not has_gmaul) or hotkey == "F6":
                f6_cfg = SWITCHER_HOTKEYS.get("F6")
                wanted_items = f6_cfg.get("items", []).copy() if f6_cfg else []
                has_mages_book = any(slot.get("item_id") == MAGES_BOOK_ID for slot in inventory.values()) or any(slot.get("item_id") == MAGES_BOOK_ID for slot in equipment.values())
                if has_mages_book:
                    wanted_items = [item for item in wanted_items if item["id"] not in (SPIRIT_SHIELD_ID, UNHOLY_BOOK_ID)]
                    if not any(item["id"] == MAGES_BOOK_ID for item in wanted_items):
                        wanted_items.append({"id": MAGES_BOOK_ID})
                skip_defender = False
                prayers_for_bracket = PRAYER_LOGIC["augury"][bracket]
                switch_gear(wanted_items, inventory, equipment, stop_event=stop_event, skip_defender=skip_defender)
                if stop_event.is_set():
                    return
                if prayers_for_bracket or protection_prayer:
                    switch_prayers(prayers_for_bracket or [], prayers, stop_event=stop_event, protection_prayer=protection_prayer)
                    if stop_event.is_set():
                        return
                spell_name = "ice_barrage" if hotkey == "F6" else "blood_barrage"
                cast_spell(spell_name, widget_state, stop_event=stop_event)
                switch_tab("spellbook", stop_event=stop_event)
                move_mouse_bezier(
                    orig_mouse[0], orig_mouse[1],
                    total_duration=rand_mouse_restore_delay(), steps=BEZIER_STEPS // 4, jitter=2
                )
                return
            # F10 (Spec Weapon/Bar) logic
            if hotkey == "F10":
                current_weapon_id = get_equipped_weapon_id(equipment)
                target_spec_weapon_ids = F10_SPEC_WEAPON_IDS
                spec_weapon_already_equipped = current_weapon_id in target_spec_weapon_ids
                if spec_weapon_already_equipped:
                    debug(f"[F10 Enhanced] Spec weapon {current_weapon_id} already equipped, skipping gear switch", always=True)
                    has_dark_bow = any(slot.get("item_id") == DARK_BOW_ID for slot in inventory.values())
                    prayers_for_bracket = PRAYER_LOGIC["rigour"][bracket] if has_dark_bow else PRAYER_LOGIC["piety"][bracket]
                else:
                    has_dark_bow = any(slot.get("item_id") == DARK_BOW_ID for slot in inventory.values())
                    if has_dark_bow:
                        has_dragon_arrow = any(slot.get("item_id") == DRAGON_ARROW_ID for slot in equipment.values())
                        already_in_wanted = any(item.get('id') == DRAGON_ARROW_ID for item in wanted_items)
                        if not has_dragon_arrow and not already_in_wanted:
                            wanted_items.append({"id": DRAGON_ARROW_ID})
                        prayers_for_bracket = PRAYER_LOGIC["rigour"][bracket]
                    else:
                        skip_defender = False
                        for slot in inventory.values():
                            if slot.get('item_id') in F10_SPEC_WEAPON_IDS:
                                skip_defender = True
                                break
                        prayers_for_bracket = PRAYER_LOGIC["piety"][bracket]
                    switch_gear(wanted_items, inventory, equipment, stop_event=stop_event, skip_defender=skip_defender)
                    if stop_event.is_set():
                        return
                if prayers_for_bracket or protection_prayer:
                    switch_prayers(prayers_for_bracket or [], prayers, stop_event=stop_event, protection_prayer=protection_prayer)
                    if stop_event.is_set():
                        return
                switch_tab("attack", stop_event=stop_event)
                ultra_accurate_sleep(rand_spec_bar_wait(), stop_event=stop_event, context="f10_spec_wait")
                ultra_accurate_sleep(rand_spec_bar_preclick(), stop_event=stop_event, context="f10_spec_preclick")
                click_spec_bar(stop_event=stop_event)
                switch_tab("inventory", stop_event=stop_event)
                move_mouse_bezier(
                    orig_mouse[0], orig_mouse[1],
                    total_duration=rand_mouse_restore_delay(), steps=BEZIER_STEPS // 4, jitter=2
                )
                return
            # F9 (2h/Defender) logic
            skip_defender = False
            prayers_for_bracket = None
            if hotkey == "F9":
                two_handed_ids = {ELDER_MAUL_ID, DHAROKS_GREATAXE_ID, VERACS_FLAIL_ID}
                equipping_2h = False
                for item in wanted_items:
                    if item.get('id') in two_handed_ids:
                        for slot_str, inv_item in inventory.items():
                            if inv_item.get('item_id') == item.get('id'):
                                equipping_2h = True
                                break
                    if equipping_2h:
                        break
                skip_defender = equipping_2h
                prayers_for_bracket = PRAYER_LOGIC["piety"][bracket]
            elif hotkey == "F8":
                prayers_for_bracket = PRAYER_LOGIC["rigour"][bracket]
            switch_gear(wanted_items, inventory, equipment, stop_event=stop_event, skip_defender=skip_defender)
            if stop_event.is_set():
                return
            if prayers_for_bracket or protection_prayer:
                switch_prayers(prayers_for_bracket or [], prayers, stop_event=stop_event, protection_prayer=protection_prayer)
                if stop_event.is_set():
                    return
            switch_tab("inventory", stop_event=stop_event)
            move_mouse_bezier(
                orig_mouse[0], orig_mouse[1],
                total_duration=rand_mouse_restore_delay(), steps=BEZIER_STEPS // 4, jitter=2
            )
        except Exception as e:
            debug(f"Exception in run_switch: {e}\n{traceback.format_exc()}", always=True)
        finally:
            stop_event.set()
            set_hotkey_sequence_active(False)
    queue_script_action(do_switch)

# ---- Auto-protect recommendation & tick logic ----
def get_recommended_protection_prayer():
    global last_emergency_switch_time
    global last_animation_id, animation_cooldown_until_tick
    global last_recommended_prayer, recommended_cooldown_until_tick

    if not is_auto_protect_enabled():
        return None

    state = game_state.get()
    opponent = state.get("opponent")
    current_tick = state.get("tick", 0)
    prayers = state.get("prayers", [])
    current_protection = get_current_protection_prayer()
    allowed_prayers = {"protect_from_magic", "protect_from_missiles", "protect_from_melee"}
    in_combat = bool(opponent)
    if not in_combat:
        last_animation_id = None
        animation_cooldown_until_tick = 0
        last_recommended_prayer = None
        recommended_cooldown_until_tick = 0
        return None
    anim_id = str(opponent.get("animation", "")) if opponent else None
    prayer_short = ANIMATION_TO_PRAYER.get(anim_id)
    prayer_from_anim = None
    if prayer_short == "magic":
        prayer_from_anim = "protect_from_magic"
    elif prayer_short == "ranged":
        prayer_from_anim = "protect_from_missiles"
    elif prayer_short == "melee":
        prayer_from_anim = "protect_from_melee"
    tick_rate = int(ANIMATION_TO_TICKRATE.get(anim_id, 0) or 0)
    if prayer_from_anim in allowed_prayers and (anim_id != last_animation_id or current_tick >= animation_cooldown_until_tick):
        last_animation_id = anim_id
        animation_cooldown_until_tick = current_tick + (tick_rate if tick_rate > 0 else 4)
        if current_protection != prayer_from_anim:
            debug(f"[Anim Prayer] Switched to {prayer_from_anim} (anim {anim_id}), cooldown {tick_rate}", always=True)
            return prayer_from_anim
        return None
    plugin_prayer = state.get("recommended_protection_prayer")
    if plugin_prayer in allowed_prayers and (current_tick >= recommended_cooldown_until_tick) and plugin_prayer != current_protection:
        last_recommended_prayer = plugin_prayer
        recommended_cooldown_until_tick = current_tick + 2
        debug(f"[PrayVsPlayer Fallback] Switched to {plugin_prayer}, 2-tick cooldown", always=True)
        return plugin_prayer
    now = time.time()
    EMERGENCY_SWITCH_MIN_INTERVAL = 0.6  # 1 game tick (600ms)

    if not current_protection and plugin_prayer in allowed_prayers:
        if now - last_emergency_switch_time < EMERGENCY_SWITCH_MIN_INTERVAL:
            return None
        last_emergency_switch_time = now
        debug(f"[PrayVsPlayer Emergency] Enabling {plugin_prayer} (none active)", always=True)
        return plugin_prayer

def can_switch_prayer(prayer_name):
    global last_prayer_switch_time, last_prayer_switch_name
    now = time.time()
    # Only allow a new switch if it's a different prayer, or the cooldown has passed
    if (prayer_name == last_prayer_switch_name) and (now - last_prayer_switch_time < PRAYER_SWITCH_COOLDOWN):
        return False
    last_prayer_switch_time = now
    last_prayer_switch_name = prayer_name
    return True

def standalone_protection_thread():
    global last_switch_tick
    global last_switch_prayer
    while True:
        time.sleep(0.05)
        if is_hotkey_sequence_active():
            continue
        recommended_prayer = get_recommended_protection_prayer()
        if not recommended_prayer:
            continue
        current_protection = get_current_protection_prayer()
        if recommended_prayer == current_protection:
            continue
        if recommended_prayer == last_switch_prayer:
            continue
        # Only once per game tick
        state = game_state.get()
        current_tick = state.get("tick", 0)
        if current_tick == last_switch_tick:
            continue
        last_switch_tick = current_tick
        if not can_switch_prayer(recommended_prayer):
            continue
        debug(f"[Prayer Switch] Switching protection: '{current_protection}' -> '{recommended_prayer}'", always=True)
        queue_script_action(lambda: standalone_protection_switch(recommended_prayer, restore_mouse_pos=True))
        last_switch_prayer = recommended_prayer

# --- Mouse click/prayer tab protection ---
def on_mouse_click(x, y, button, pressed):
    state = game_state.get()
    current_tab = state.get("tab")
    if pressed and button == pynput_mouse.Button.left and current_tab == "prayer" and is_auto_protect_enabled():
        debug("[INPUT BLOCK] Blocked user click on prayer tab due to auto-protect", always=True)
        return
    if pressed and button == pynput_mouse.Button.left:
        if (current_tab == "prayer" or current_tab == "inventory") and (should_block_prayer_clicks() or is_action_locked()):
            return

def prayer_tab_blocking_thread():
    while True:
        try:
            time.sleep(0.01)
            state = game_state.get()
            current_tab = state.get("tab")
            if current_tab and current_tab.lower() == "prayer":
                if should_block_prayer_clicks() or is_action_locked():
                    if win32api.GetKeyState(win32con.VK_LBUTTON) < 0:
                        win32api.mouse_event(win32con.MOUSEEVENTF_LEFTUP, 0, 0)
                        debug(f"[Click Block] Mouse release forced on prayer tab (click blocked)", always=True)
                        time.sleep(0.05)
        except Exception as e:
            debug(f"[Click Block] Error in prayer_tab_blocking_thread: {e}", always=True)
            time.sleep(0.1)

def mouse_click_listener_thread():
    with pynput_mouse.Listener(on_click=on_mouse_click) as listener:
        listener.join()

# --- Keyboard/hotkey handling ---
hotkey_lock = threading.Lock()
current_switch_thread = None
current_stop_event = None

def get_equipped_weapon_id(equipment):
    weapon_slot = equipment.get("3", {})
    return weapon_slot.get("item_id", -1)

def on_press(key):
    try:
        fkeys = [
            pynput_keyboard.Key.f6, pynput_keyboard.Key.f7, pynput_keyboard.Key.f8,
            pynput_keyboard.Key.f9, pynput_keyboard.Key.f10, pynput_keyboard.Key.f11, pynput_keyboard.Key.f12
        ]
        if key in fkeys:
            hk = f"F{6 + fkeys.index(key)}"
            if hk in SWITCHER_HOTKEYS:
                with hotkey_lock:
                    global current_switch_thread, current_stop_event
                    if current_switch_thread and current_switch_thread.is_alive():
                        current_stop_event.set()
                        t0 = time.time()
                        while current_switch_thread.is_alive() and time.time() - t0 < 1.0:
                            time.sleep(0.01)
                    stop_event = threading.Event()
                    thread = threading.Thread(target=run_switch, args=(hk, stop_event), daemon=True)
                    current_switch_thread = thread
                    current_stop_event = stop_event
                    thread.start()
        if hasattr(key, 'char') and key.char and key.char.lower() == 'x':
            try:
                import pygetwindow
                win = pygetwindow.getActiveWindow()
                if win and win.title and win.title.startswith("RuneLite"):
                    with auto_protect_lock:
                        global auto_protect_enabled
                        auto_protect_enabled = not auto_protect_enabled
                        debug(f"[Click Block] Auto-protection toggled to {'ON' if auto_protect_enabled else 'OFF'}", always=True)
            except Exception as e:
                debug(f"pygetwindow error: {e}", always=True)
        if key == pynput_keyboard.Key.esc:
            os._exit(0)
    except Exception as e:
        debug(f"Exception in on_press: {e}\n{traceback.format_exc()}", always=True)

# --- Websocket/game state listener (already in Section 1) ---
def ws_listener():
    while True:
        try:
            ws = websocket.WebSocketApp(
                "ws://127.0.0.1:8765",
                on_message=lambda ws, message: game_state.update(json.loads(message))
            )
            ws.run_forever()
        except Exception as err:
            debug(f"WS connection error: {err}. Reconnecting in 2s.", always=True)
            time.sleep(2)

# --- Misc/utility threads ---
def watchdog_thread():
    while True:
        time.sleep(3)

def hang_detection_thread():
    while True:
        time.sleep(5)
        with hotkey_lock:
            if current_switch_thread and current_switch_thread.is_alive():
                debug(f"[HANG DETECTION] Switch thread {current_switch_thread.ident} has been alive for more than 5s!", always=True)

# --- MAIN ---
def main():
    debug("RuneLite AutoSwitcher started with PRAYER COORDINATION. Press ESC to exit.", always=True)
    debug("Features: Click protection, no misclicks, seamless switching, smart prayer handling", always=True)
    debug("Press 'X' to toggle auto-protection ON/OFF", always=True)
    bring_runelite_to_front_and_position()
    threading.Thread(target=ws_listener, daemon=True).start()
    threading.Thread(target=watchdog_thread, daemon=True).start()
    threading.Thread(target=hang_detection_thread, daemon=True).start()
    threading.Thread(target=standalone_protection_thread, daemon=True).start()
    threading.Thread(target=mouse_click_listener_thread, daemon=True).start()
    threading.Thread(target=prayer_tab_blocking_thread, daemon=True).start()
    threading.Thread(target=action_queue_thread, daemon=True).start()
    with pynput_keyboard.Listener(on_press=on_press) as listener:
        listener.join()

if __name__ == '__main__':
    main()
