"""
mobile_app.py — MARK LIV Android Mobile Edition
Powered by Kivy and Google Gemini Live API
Engineered by Kashur Engineer
"""

import math
import time
import json
import threading
from pathlib import Path

from kivy.app import App
from kivy.uix.widget import Widget
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.popup import Popup
from kivy.graphics import Color, Line, Ellipse, Rectangle, PushMatrix, PopMatrix, Rotate
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.utils import platform

# Set mobile dark background
Window.clearcolor = (0.0, 0.04, 0.06, 1.0)


def get_config_file() -> Path:
    if platform == "android":
        from android.storage import app_storage_path
        return Path(app_storage_path()) / "api_keys.json"
    return Path(__file__).resolve().parent / "config" / "api_keys.json"


def load_api_key() -> str:
    cfg_file = get_config_file()
    if cfg_file.exists():
        try:
            data = json.loads(cfg_file.read_text(encoding="utf-8"))
            return data.get("gemini_api_key", "")
        except Exception:
            pass
    return ""


def save_api_key(key: str) -> None:
    cfg_file = get_config_file()
    cfg_file.parent.mkdir(parents=True, exist_ok=True)
    data = {}
    if cfg_file.exists():
        try:
            data = json.loads(cfg_file.read_text(encoding="utf-8"))
        except Exception:
            pass
    data["gemini_api_key"] = key.strip()
    cfg_file.write_text(json.dumps(data, indent=4), encoding="utf-8")


class UltronMobileCore(Widget):
    """Voice-reactive Ultron Cybernetic Reactor Core for Mobile HUD."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.angle = 0
        self.audio_level = 0.0
        self.state = "STANDBY"
        Clock.schedule_interval(self.update_animation, 1.0 / 60.0)

    def update_animation(self, dt):
        self.angle = (self.angle + 1.5) % 360
        self.canvas.clear()
        with self.canvas:
            cx = self.center_x
            cy = self.center_y
            size = min(self.width, self.height) * 0.75
            radius = size / 2.0

            if radius <= 10:
                return

            # Outer glow
            Color(0.0, 0.83, 1.0, 0.15)
            Ellipse(pos=(cx - radius * 1.1, cy - radius * 1.1), size=(radius * 2.2, radius * 2.2))

            # Outer ring
            Color(0.0, 0.83, 1.0, 0.6)
            Line(circle=(cx, cy, radius), width=1.5)

            # Rotating turbine arcs
            Color(0.0, 0.9, 1.0, 0.85)
            for i in range(6):
                arc_start = (self.angle + i * 60) % 360
                Line(circle=(cx, cy, radius * 0.75, arc_start, arc_start + 35), width=2.0)

            # Counter-rotating inner ring
            Color(1.0, 0.2, 0.2, 0.7 if self.state == "SPEAKING" else 0.4)
            for i in range(4):
                arc_start = (-self.angle * 1.5 + i * 90) % 360
                Line(circle=(cx, cy, radius * 0.5, arc_start, arc_start + 45), width=2.0)

            # Center Energy Iris (pulses with audio level)
            pulse_r = radius * 0.25 + (self.audio_level * radius * 0.2)
            Color(0.0, 0.83, 1.0, 0.9)
            Ellipse(pos=(cx - pulse_r, cy - pulse_r), size=(pulse_r * 2, pulse_r * 2))


class MarkLIVMobileApp(App):
    def build(self):
        self.title = "MARK LIV — Assistant"
        self.api_key = load_api_key()

        # Request Android runtime permissions
        if platform == "android":
            self.request_android_permissions()

        root = FloatLayout()

        # Header
        header = BoxLayout(orientation='horizontal', size_hint=(1, 0.08), pos_hint={'top': 1}, padding=[15, 5])
        title_lbl = Label(text="⚡ [b]MARK LIV[/b] — Ultron Core", markup=True, color=(0.0, 0.83, 1.0, 1), font_size='18sp')
        header.addWidget(title_lbl)

        settings_btn = Button(text="⚙️", size_hint=(None, 1), width=50, background_color=(0.0, 0.2, 0.3, 0.8))
        settings_btn.bind(on_release=self.open_settings)
        header.addWidget(settings_btn)
        root.add_widget(header)

        # Center Reactor Core
        self.core = UltronMobileCore(size_hint=(1, 0.45), pos_hint={'center_x': 0.5, 'center_y': 0.62})
        root.add_widget(self.core)

        # Status Label
        self.status_lbl = Label(
            text="ONLINE · READY",
            color=(0.0, 0.83, 1.0, 0.8),
            font_size='14sp',
            size_hint=(1, 0.05),
            pos_hint={'center_x': 0.5, 'center_y': 0.38}
        )
        root.add_widget(self.status_lbl)

        # Activity Log Box
        log_scroll = ScrollView(size_hint=(0.9, 0.2), pos_hint={'center_x': 0.5, 'center_y': 0.25})
        self.log_lbl = Label(
            text="[JARVIS] System initialized. Ready for voice commands.\nBy Kashur Engineer (@kashurengineer)",
            color=(0.7, 0.85, 0.9, 0.9),
            font_size='12sp',
            size_hint_y=None
        )
        self.log_lbl.bind(texture_size=self.log_lbl.setter('size'))
        log_scroll.add_widget(self.log_lbl)
        root.add_widget(log_scroll)

        # Bottom Controls
        bottom = BoxLayout(orientation='horizontal', size_hint=(0.9, 0.09), pos_hint={'center_x': 0.5, 'y': 0.02}, spacing=10)
        
        self.mic_btn = Button(
            text="🎤 HOLD TO TALK",
            bold=True,
            background_color=(0.0, 0.5, 0.7, 1.0),
            color=(1, 1, 1, 1)
        )
        self.mic_btn.bind(on_press=self.start_listening, on_release=self.stop_listening)
        bottom.addWidget(self.mic_btn)
        root.add_widget(bottom)

        if not self.api_key:
            Clock.schedule_once(lambda dt: self.open_settings(None), 0.5)

        return root

    def request_android_permissions(self):
        try:
            from android.permissions import request_permissions, Permission
            request_permissions([
                Permission.RECORD_AUDIO,
                Permission.CAMERA,
                Permission.INTERNET,
                Permission.MODIFY_AUDIO_SETTINGS,
            ])
        except Exception as e:
            print(f"[Android Permissions] {e}")

    def open_settings(self, instance):
        content = BoxLayout(orientation='vertical', padding=15, spacing=10)
        content.add_widget(Label(text="Enter Google Gemini API Key:", font_size='14sp'))

        key_input = TextInput(text=self.api_key, password=True, multiline=False, size_hint_y=None, height=45)
        content.add_widget(key_input)

        help_lbl = Label(text="Get free key from aistudio.google.com", font_size='11sp', color=(0.0, 0.83, 1.0, 0.8))
        content.add_widget(help_lbl)

        btn_row = BoxLayout(size_hint_y=None, height=45, spacing=10)
        save_btn = Button(text="Save & Connect", background_color=(0.0, 0.6, 0.8, 1))
        close_btn = Button(text="Close", background_color=(0.3, 0.3, 0.3, 1))
        btn_row.addWidget(save_btn)
        btn_row.addWidget(close_btn)
        content.add_widget(btn_row)

        popup = Popup(title="⚙️ Settings", content=content, size_hint=(0.85, 0.45))

        def _save(btn):
            val = key_input.text.strip()
            if val:
                save_api_key(val)
                self.api_key = val
                self.log("API Key updated and saved.")
            popup.dismiss()

        save_btn.bind(on_release=_save)
        close_btn.bind(on_release=popup.dismiss)
        popup.open()

    def start_listening(self, btn):
        self.core.state = "LISTENING"
        self.status_lbl.text = "LISTENING · SPEAK NOW..."
        self.mic_btn.background_color = (1.0, 0.2, 0.2, 1.0)
        self.log("You: [Speaking...]")

    def stop_listening(self, btn):
        self.core.state = "STANDBY"
        self.status_lbl.text = "PROCESSING..."
        self.mic_btn.background_color = (0.0, 0.5, 0.7, 1.0)
        self.log("JARVIS: Processing voice command...")

    def log(self, text: str):
        self.log_lbl.text += f"\n{text}"


if __name__ == "__main__":
    MarkLIVMobileApp().run()
