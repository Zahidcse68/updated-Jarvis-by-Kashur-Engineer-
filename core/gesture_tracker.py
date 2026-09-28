"""
core/gesture_tracker.py — JARVIS Hand Gesture & Motion Tracking Engine

Provides hands-free camera gesture control for the HUD:
1. **Swipe Left / Right / Up / Down** (1 hand fast lateral motion) -> Swipes HUD panels/modes.
2. **2-Hand Zoom / Scale** (Distance between 2 hands) -> Zooms HUD / Hologram in & out.
3. **Hand Pan / Hover** (1 hand position) -> Tilts 3D Avatar gaze and moves HUD focus.

Uses MediaPipe Hands when available, with fast OpenCV optical-centroid fallback.
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
        on_zoom: Optional[Callable[[float], None]] = None,
        on_pan: Optional[Callable[[float, float], None]] = None,
        on_frame: Optional[Callable[[np.ndarray], None]] = None,
    ):
        self.camera_index = camera_index
        self.on_swipe = on_swipe       # callback(direction: "left"|"right"|"up"|"down")
        self.on_zoom  = on_zoom        # callback(scale_factor: float) e.g. 0.6 to 2.5
        self.on_pan   = on_pan         # callback(dx: float, dy: float)
        self.on_frame = on_frame       # callback(frame_bgr: np.ndarray)

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._cap: Optional[cv2.VideoCapture] = None

        # Tracking state
        self._last_x: Optional[float] = None
        self._last_y: Optional[float] = None
        self._last_pos_time: float = 0.0
        self._last_swipe_time: float = 0.0
        self._swipe_cooldown: float = 0.45  # seconds between swiping actions

        # 2-hand zoom baseline
        self._prev_hand_dist: Optional[float] = None
        self._current_scale: float = 1.0
        self._min_scale: float = 0.5
        self._max_scale: float = 2.5

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

    # ── MEDIAPIPE HIGH-PRECISION TRACKING ──────────────────────────────────────
    def _mediapipe_loop(self) -> None:
        hands = _MP_HANDS.Hands(
            static_image_mode=False,
            max_num_hands=2,
            min_detection_confidence=0.6,
            min_tracking_confidence=0.5,
        )

        try:
            while self._running:
                ret, frame = self._cap.read()
                if not ret or frame is None:
                    time.sleep(0.03)
                    continue

                # Flip horizontally for intuitive mirror view
                frame = cv2.flip(frame, 1)
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                results = hands.process(rgb)

                now = time.time()

                if results.multi_hand_landmarks:
                    num_hands = len(results.multi_hand_landmarks)

                    if num_hands == 2:
                        # ── TWO HANDS DETECTED: PINCH / SPREAD ZOOM ──
                        h1 = results.multi_hand_landmarks[0].landmark[9]  # middle finger mcp
                        h2 = results.multi_hand_landmarks[1].landmark[9]

                        # 2D Euclidean distance in normalised screen coordinates
                        dist = math.hypot(h1.x - h2.x, h1.y - h2.y)

                        if self._prev_hand_dist is not None:
                            delta = dist - self._prev_hand_dist
                            if abs(delta) > 0.008:
                                # Scale sensitivity factor
                                self._current_scale = max(self._min_scale, min(self._max_scale, self._current_scale + delta * 2.8))
                                if self.on_zoom:
                                    try:
                                        self.on_zoom(self._current_scale)
                                    except Exception:
                                        pass

                        self._prev_hand_dist = dist
                        self._last_x = None
                        self._last_y = None

                    elif num_hands == 1:
                        # ── ONE HAND DETECTED: SWIPE & PAN GESTURES ──
                        self._prev_hand_dist = None
                        h = results.multi_hand_landmarks[0].landmark[9]
                        curr_x, curr_y = h.x, h.y

                        if self._last_x is not None and self._last_y is not None:
                            dt = now - self._last_pos_time
                            if 0.015 < dt < 0.35:
                                dx = curr_x - self._last_x
                                dy = curr_y - self._last_y
                                vel_x = dx / dt
                                vel_y = dy / dt

                                # Swipe velocity threshold (normalised screen units/sec)
                                if now - self._last_swipe_time > self._swipe_cooldown:
                                    if abs(vel_x) > 1.8 and abs(vel_x) > abs(vel_y) * 1.4:
                                        direction = "right" if vel_x > 0 else "left"
                                        self._last_swipe_time = now
                                        if self.on_swipe:
                                            try:
                                                self.on_swipe(direction)
                                            except Exception:
                                                pass
                                    elif abs(vel_y) > 2.0 and abs(vel_y) > abs(vel_x) * 1.4:
                                        direction = "down" if vel_y > 0 else "up"
                                        self._last_swipe_time = now
                                        if self.on_swipe:
                                            try:
                                                self.on_swipe(direction)
                                            except Exception:
                                                pass

                                # Subtle avatar head look/pan
                                if self.on_pan:
                                    try:
                                        # Map 0.0..1.0 to -1.0..+1.0
                                        pan_x = (curr_x - 0.5) * 2.0
                                        pan_y = (curr_y - 0.5) * 2.0
                                        self.on_pan(pan_x, pan_y)
                                    except Exception:
                                        pass

                        self._last_x = curr_x
                        self._last_y = curr_y
                        self._last_pos_time = now
                else:
                    self._prev_hand_dist = None
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

    # ── OPENCV CENTROID FALLBACK (NO DEPENDENCY) ──────────────────────────────
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

            # Morphological cleaning
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)

            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            valid_cnts = [c for c in contours if cv2.contourArea(c) > 3000]

            now = time.time()

            if len(valid_cnts) >= 2:
                # 2 Hands / Motions: Zoom
                c1 = max(valid_cnts, key=cv2.contourArea)
                c2 = sorted(valid_cnts, key=cv2.contourArea, reverse=True)[1]
                m1, m2 = cv2.moments(c1), cv2.moments(c2)
                if m1["m00"] > 0 and m2["m00"] > 0:
                    x1, y1 = int(m1["m10"] / m1["m00"]), int(m1["m01"] / m1["m00"])
                    x2, y2 = int(m2["m10"] / m2["m00"]), int(m2["m01"] / m2["m00"])
                    dist = math.hypot(x1 - x2, y1 - y2) / w
                    if self._prev_hand_dist is not None:
                        delta = dist - self._prev_hand_dist
                        if abs(delta) > 0.01:
                            self._current_scale = max(self._min_scale, min(self._max_scale, self._current_scale + delta * 2.5))
                            if self.on_zoom:
                                self.on_zoom(self._current_scale)
                    self._prev_hand_dist = dist

            elif len(valid_cnts) == 1:
                self._prev_hand_dist = None
                m = cv2.moments(valid_cnts[0])
                if m["m00"] > 0:
                    cx = (m["m10"] / m["m00"]) / w
                    cy = (m["m01"] / m["m00"]) / h

                    if self._last_x is not None and self._last_y is not None:
                        dt = now - self._last_pos_time
                        if 0.02 < dt < 0.4:
                            dx = cx - self._last_x
                            vel_x = dx / dt
                            if now - self._last_swipe_time > self._swipe_cooldown:
                                if abs(vel_x) > 1.6:
                                    direction = "right" if vel_x > 0 else "left"
                                    self._last_swipe_time = now
                                    if self.on_swipe:
                                        self.on_swipe(direction)

                    self._last_x = cx
                    self._last_y = cy
                    self._last_pos_time = now
            else:
                self._prev_hand_dist = None
                self._last_x = None

            time.sleep(0.02)
