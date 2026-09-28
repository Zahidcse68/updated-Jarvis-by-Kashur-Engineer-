"""
core/gesture_tracker.py — JARVIS Hand Swipe & Gesture Engine

Dedicated hands-free camera gesture engine for HUD switching:
1. **Swipe Left (`◀`)** -> Switches HUD to Ultron Arc Reactor Core.
2. **Swipe Right (`▶`)** -> Switches HUD to 3D Holographic Face Avatar.
3. **Swipe Up (`▲`)** -> Cycles HUD Theme Colors (JARVIS Cyan, Ultron Crimson, Mark LIV Gold, etc.).
4. **Swipe Down (`▼`)** -> Toggles Quick Drawer & Panels.
5. **Hand Pan / Hover** -> Tilts 3D Avatar gaze smoothly towards hand position.

Fast, low-latency, and optimized for laptop webcams with MediaPipe Hands and OpenCV fallback.
"""

from __future__ import annotations

import math
import threading
import time
from typing import Callable, Optional

import cv2
import numpy as np

# Optional MediaPipe import
try:
    import mediapipe as mp
    _MP_HANDS = mp.solutions.hands
    _HAVE_MEDIAPIPE = True
except Exception:
    _MP_HANDS = None
    _HAVE_MEDIAPIPE = False


class GestureTracker:
    def __init__(
        self,
        camera_index: int = 0,
        on_swipe: Optional[Callable[[str], None]] = None,
        on_pan: Optional[Callable[[float, float], None]] = None,
        on_frame: Optional[Callable[[np.ndarray], None]] = None,
    ):
        self.camera_index = camera_index
        self.on_swipe = on_swipe       # callback(direction: "left"|"right"|"up"|"down")
        self.on_pan   = on_pan         # callback(dx: float, dy: float)
        self.on_frame = on_frame       # callback(frame_bgr: np.ndarray)

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._cap: Optional[cv2.VideoCapture] = None

        # Hand tracking & swipe velocity state
        self._last_x: Optional[float] = None
        self._last_y: Optional[float] = None
        self._last_pos_time: float = 0.0
        self._last_swipe_time: float = 0.0
        self._swipe_cooldown: float = 0.35  # seconds between swipe triggers

    @property
    def is_running(self) -> bool:
        return self._running

    def start(self) -> bool:
        if self._running:
            return True
        self._running = True
        self._thread = threading.Thread(target=self._worker_loop, daemon=True, name="jarvis-gesture-tracker")
        self._thread.start()
        return True

    def stop(self) -> None:
        self._running = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.0)
        self._thread = None
        if self._cap:
            try:
                self._cap.release()
            except Exception:
                pass
            self._cap = None

    def _worker_loop(self) -> None:
        try:
            self._cap = cv2.VideoCapture(self.camera_index, cv2.CAP_DSHOW if hasattr(cv2, 'CAP_DSHOW') else cv2.CAP_ANY)
            if not self._cap.isOpened():
                self._cap = cv2.VideoCapture(self.camera_index)
            
            if not self._cap.isOpened():
                self._running = False
                return

            self._cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
            self._cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
            self._cap.set(cv2.CAP_PROP_FPS, 30)

            if _HAVE_MEDIAPIPE:
                self._mediapipe_loop()
            else:
                self._opencv_fallback_loop()

        except Exception:
            pass
        finally:
            if self._cap:
                try:
                    self._cap.release()
                except Exception:
                    pass
                self._cap = None
            self._running = False

    # ── MEDIAPIPE HIGH-PRECISION HAND SWIPE TRACKING ───────────────────────────
    def _mediapipe_loop(self) -> None:
        hands = _MP_HANDS.Hands(
            static_image_mode=False,
            max_num_hands=1,
            min_detection_confidence=0.50,
            min_tracking_confidence=0.50,
        )

        try:
            while self._running:
                ret, frame = self._cap.read()
                if not ret or frame is None:
                    time.sleep(0.03)
                    continue

                # Flip horizontally for natural mirror behavior
                frame = cv2.flip(frame, 1)
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                results = hands.process(rgb)

                now = time.time()

                if results.multi_hand_landmarks:
                    h = results.multi_hand_landmarks[0].landmark[9]  # Palm center
                    curr_x, curr_y = h.x, h.y

                    if self._last_x is not None and self._last_y is not None:
                        dt = now - self._last_pos_time
                        if 0.015 < dt < 0.35:
                            dx = curr_x - self._last_x
                            dy = curr_y - self._last_y
                            vel_x = dx / dt
                            vel_y = dy / dt

                            # Swipe detection
                            if now - self._last_swipe_time > self._swipe_cooldown:
                                if abs(vel_x) > 1.2 and abs(vel_x) > abs(vel_y) * 1.2:
                                    direction = "right" if vel_x > 0 else "left"
                                    self._last_swipe_time = now
                                    if self.on_swipe:
                                        try:
                                            self.on_swipe(direction)
                                        except Exception:
                                            pass
                                elif abs(vel_y) > 1.3 and abs(vel_y) > abs(vel_x) * 1.2:
                                    direction = "down" if vel_y > 0 else "up"
                                    self._last_swipe_time = now
                                    if self.on_swipe:
                                        try:
                                            self.on_swipe(direction)
                                        except Exception:
                                            pass

                            # Smooth avatar glance pan
                            if self.on_pan:
                                try:
                                    pan_x = (curr_x - 0.5) * 2.0
                                    pan_y = (curr_y - 0.5) * 2.0
                                    self.on_pan(pan_x, pan_y)
                                except Exception:
                                    pass

                    self._last_x = curr_x
                    self._last_y = curr_y
                    self._last_pos_time = now
                else:
                    self._last_x = None
                    self._last_y = None

                if self.on_frame:
                    try:
                        self.on_frame(frame)
                    except Exception:
                        pass

                time.sleep(0.016)

        finally:
            hands.close()

    # ── OPENCV CENTROID SWIPE TRACKING FALLBACK ────────────────────────────────
    def _opencv_fallback_loop(self) -> None:
        bg_sub = cv2.createBackgroundSubtractorMOG2(history=30, varThreshold=36, detectShadows=False)

        while self._running:
            ret, frame = self._cap.read()
            if not ret or frame is None:
                time.sleep(0.03)
                continue

            frame = cv2.flip(frame, 1)
            h, w = frame.shape[:2]
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            blur = cv2.GaussianBlur(gray, (21, 21), 0)
            mask = bg_sub.apply(blur)

            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)

            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            valid_cnts = [c for c in contours if cv2.contourArea(c) > 3000]

            now = time.time()

            if valid_cnts:
                c = max(valid_cnts, key=cv2.contourArea)
                m = cv2.moments(c)
                if m["m00"] > 0:
                    curr_x = (m["m10"] / m["m00"]) / w
                    curr_y = (m["m01"] / m["m00"]) / h

                    if self._last_x is not None and self._last_y is not None:
                        dt = now - self._last_pos_time
                        if 0.015 < dt < 0.35:
                            dx = curr_x - self._last_x
                            dy = curr_y - self._last_y
                            vel_x = dx / dt
                            vel_y = dy / dt

                            if now - self._last_swipe_time > self._swipe_cooldown:
                                if abs(vel_x) > 1.2 and abs(vel_x) > abs(vel_y) * 1.2:
                                    direction = "right" if vel_x > 0 else "left"
                                    self._last_swipe_time = now
                                    if self.on_swipe:
                                        try:
                                            self.on_swipe(direction)
                                        except Exception:
                                            pass
                                elif abs(vel_y) > 1.3 and abs(vel_y) > abs(vel_x) * 1.2:
                                    direction = "down" if vel_y > 0 else "up"
                                    self._last_swipe_time = now
                                    if self.on_swipe:
                                        try:
                                            self.on_swipe(direction)
                                        except Exception:
                                            pass

                            if self.on_pan:
                                try:
                                    pan_x = (curr_x - 0.5) * 2.0
                                    pan_y = (curr_y - 0.5) * 2.0
                                    self.on_pan(pan_x, pan_y)
                                except Exception:
                                    pass

                    self._last_x = curr_x
                    self._last_y = curr_y
                    self._last_pos_time = now
            else:
                self._last_x = None
                self._last_y = None

            if self.on_frame:
                try:
                    self.on_frame(frame)
                except Exception:
                    pass

            time.sleep(0.016)
