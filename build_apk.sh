#!/bin/bash
# ==============================================================================
# MARK LIV — 1-Click Android APK Build Script (Buildozer / Kivy)
# Run this in Google Colab (https://colab.research.google.com) or Ubuntu Linux
# ==============================================================================

set -e

echo "📦 [1/4] Installing system dependencies and Android tools..."
sudo apt update -qq
sudo apt install -y -qq git zip unzip openjdk-17-jdk python3-pip autoconf libtool pkg-config zlib1g-dev libncurses5-dev libncursesw5-dev libtinfo5 cmake libffi-dev libssl-dev build-essential ccache

echo "🐍 [2/4] Installing Python build tools (Cython & Buildozer)..."
pip install --upgrade pip
pip install Cython==0.29.36 buildozer virtualenv

echo "⚙️ [3/4] Preparing project for mobile APK..."
cp mobile_app.py main_mobile.py

echo "🚀 [4/4] Compiling Android APK (this may take 5-10 minutes on first run)..."
buildozer -v android debug

echo "🎉 Build complete! Your APK is ready in the 'bin/' folder:"
ls -lh bin/*.apk
