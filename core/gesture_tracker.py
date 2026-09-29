"""
core/gesture_tracker.py — JARVIS Hand Gesture, Finger Pointer & HUD Control Engine

Features:
1. **🖐️ Hand Swipe (`◀` / `▶` / `▲` / `▼`)**:
   - Switches between the 2 HUD modes (JARVIS 3D Face Avatar vs Ultron Arc Reactor).
2. **☝️ 1-Finger Point & Drag**:
   - Move & drag floating news cards across the screen with your finger in real time.
3. **✌️ 2-Fingers Gesture (Index + Middle extended)**:
   - Opens / Expands the news card under the finger with holographic audio chime!
4. **👀 Hand Pan / Hover**:
   - Tilts 3D Avatar gaze towards hand position.
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
        on_finger_pointer: Optional[Callable[[float, float, str], None]] = None,
        on_frame: Optional[Callable[[np.ndarray], None]] = None,
    ):
        self.camera_index = camera_index
        self.on_swipe = on_swipe                 # callback(direction: "left"|"right"|"up"|"down")
        self.on_pan   = on_pan                   # callback(dx: float, dy: float)
        self.on_finger_pointer = on_finger_pointer # callback(norm_x: float, norm_y: float, action: "move"|"open_2finger"|"release")
        self.on_frame = on_frame                 # callback(frame_bgr: np.ndarray)

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._cap: Optional[cv2.VideoCapture] = None

        # Swipe tracking state
        self._last_x: Optional[float] = None
        self._last_y: Optional[float] = None
        self._last_pos_time: float = 0.0
        self._last_swipe_time: float = 0.0
        self._swipe_cooldown: float = 0.40

        # 2-finger open debounce
        self._last_open_time: float = 0.0

        # Smoothed pointer coordinates
        self._smooth_px: Optional[float] = None
        self._smooth_py: Optional[float] = None

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

    # ── MEDIAPIPE FINGER & SWIPE RECOGNITION ───────────────────────────────────
    def _mediapipe_loop(self) -> None:
        hands = _MP_HANDS.Hands(
            static_image_mode=False,
            max_num_hands=1,
            min_detection_confidence=0.55,
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
                    lms = results.multi_hand_landmarks[0].landmark
                    palm = lms[9]  # Palm center
                    curr_x, curr_y = palm.x, palm.y

                    # Finger keypoints
                    idx_tip = lms[8]
                    idx_dip = lms[6]
                    mid_tip = lms[12]
                    mid_dip = lms[10]
                    rng_tip = lms[16]
                    rng_dip = lms[14]
                    pnk_tip = lms[20]
                    pnk_dip = lms[18]

                    # Detect extended fingers (relative to DIP joints)
                    idx_ext = idx_tip.y < idx_dip.y
                    mid_ext = mid_tip.y < mid_dip.y
                    rng_ext = rng_tip.y < rng_dip.y
                    pnk_ext = pnk_tip.y < pnk_dip.y

                    is_full_hand   = idx_ext and mid_ext and rng_ext and pnk_ext
                    is_index_pinky = idx_ext and pnk_ext and not mid_ext and not rng_ext  # 🤘 Pick news with Index + Last Finger
                    is_2finger     = idx_ext and mid_ext and not rng_ext and not pnk_ext  # ✌️ 2-finger open
                    is_1finger     = idx_ext and not mid_ext and not rng_ext and not pnk_ext # ☝️ 1-finger point

                    # 1. Check for Index Finger + Last Finger (Pinky 🤘) -> PICK / GRAB & MOVE NEWS
                    if is_index_pinky:
                        raw_px = (idx_tip.x + pnk_tip.x) / 2.0
                        raw_py = (idx_tip.y + pnk_tip.y) / 2.0
                        if self._smooth_px is None:
                            self._smooth_px, self._smooth_py = raw_px, raw_py
                        else:
                            self._smooth_px += (raw_px - self._smooth_px) * 0.45
                            self._smooth_py += (raw_py - self._smooth_py) * 0.45

                        if self.on_finger_pointer:
                            try:
                                self.on_finger_pointer(self._smooth_px, self._smooth_py, "pick")
                            except Exception:
                                pass

                    # 2. Check for 2-Fingers Extended (Index + Middle ✌️) -> OPEN NEWS
                    elif is_2finger:
                        if now - self._last_open_time > 0.65:
                            self._last_open_time = now
                            if self.on_finger_pointer:
                                try:
                                    self.on_finger_pointer(idx_tip.x, idx_tip.y, "open_2finger")
                                except Exception:
                                    pass

                    # 3. Check for 1-Finger Pointing (Index ☝️) -> MOVE / DRAG NEWS
                    elif is_1finger:
                        raw_px, raw_py = idx_tip.x, idx_tip.y
                        if self._smooth_px is None:
                            self._smooth_px, self._smooth_py = raw_px, raw_py
                        else:
                            self._smooth_px += (raw_px - self._smooth_px) * 0.45
                            self._smooth_py += (raw_py - self._smooth_py) * 0.45

                        if self.on_finger_pointer:
                            try:
                                self.on_finger_pointer(self._smooth_px, self._smooth_py, "move")
                            except Exception:
                                pass

                    # 4. FULL HAND SWIPE ONLY: When all fingers are open (🖐️ full palm), fast swipe switches HUD
                    elif is_full_hand:
                        self._smooth_px = None
                        self._smooth_py = None
                        if self._last_x is not None and self._last_y is not None:
                            dt = now - self._last_pos_time
                            if 0.015 < dt < 0.35:
                                dx = curr_x - self._last_x
                                dy = curr_y - self._last_y
                                vel_x = dx / dt
                                vel_y = dy / dt

                                # Swipe threshold (fast full hand displacement)
                                if now - self._last_swipe_time > self._swipe_cooldown:
                                    if abs(vel_x) > 1.50 and abs(vel_x) > abs(vel_y) * 1.3:
                                        direction = "right" if vel_x > 0 else "left"
                                        self._last_swipe_time = now
                                        if self.on_swipe:
                                            try:
                                                self.on_swipe(direction)
                                            except Exception:
                                                pass
                                    elif abs(vel_y) > 1.60 and abs(vel_y) > abs(vel_x) * 1.3:
                                        direction = "down" if vel_y > 0 else "up"
                                        self._last_swipe_time = now
                                        if self.on_swipe:
                                            try:
                                                self.on_swipe(direction)
                                            except Exception:
                                                pass

                    # Smooth avatar glance pan
                    if self.on_pan and (is_full_hand or is_index_pinky or is_1finger):
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
                    self._smooth_px = None
                    self._smooth_py = None
                    if self._last_x is not None:
                        if self.on_finger_pointer:
                            try:
                                self.on_finger_pointer(0.5, 0.5, "release")
                            except Exception:
                                pass
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

    # ── OPENCV CENTROID TRACKING FALLBACK ──────────────────────────────────────
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

                    if self.on_finger_pointer:
                        try:
                            self.on_finger_pointer(curr_x, curr_y, "move")
                        except Exception:
                            pass

                    if self._last_x is not None and self._last_y is not None:
                        dt = now - self._last_pos_time
                        if 0.015 < dt < 0.35:
                            dx = curr_x - self._last_x
                            dy = curr_y - self._last_y
                            vel_x = dx / dt
                            vel_y = dy / dt

                            if now - self._last_swipe_time > self._swipe_cooldown:
                                if abs(vel_x) > 1.35 and abs(vel_x) > abs(vel_y) * 1.3:
                                    direction = "right" if vel_x > 0 else "left"
                                    self._last_swipe_time = now
                                    if self.on_swipe:
                                        try:
                                            self.on_swipe(direction)
                                        except Exception:
                                            pass
                                elif abs(vel_y) > 1.45 and abs(vel_y) > abs(vel_x) * 1.3:
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
