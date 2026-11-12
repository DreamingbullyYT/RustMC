#!/usr/bin/env python3
"""Diagnostic-only Android Vulkan FIFO present-mode override.

Enable explicitly with HORIZON_ANDROID_VULKAN_FIFO_TEST=1. This edits only the
throwaway upstream checkout prepared for an Android build; it never changes the
pinned upstream revision or the tracked engine sources in this repository.
"""
import os
from pathlib import Path
import sys

if os.environ.get("HORIZON_ANDROID_VULKAN_FIFO_TEST") != "1":
    print("Android Vulkan FIFO diagnostic override skipped (not enabled).")
    raise SystemExit(0)

root = Path(sys.argv[1])
relative = Path("crates/horizon-client-render/src/gfx/surface_setup.rs")
path = root / relative
source = path.read_text(encoding="utf-8")

old = """                window.inner_size(),
                None,
                true,"""
new = """                window.inner_size(),
                Some(wgpu::PresentMode::Fifo),
                true,"""

if source.count(new) == 1:
    print("Android Vulkan FIFO diagnostic override already applied.")
elif source.count(old) == 1:
    path.write_text(source.replace(old, new, 1), encoding="utf-8")
    print("Android Vulkan FIFO diagnostic override applied to transient upstream checkout.")
else:
    raise SystemExit(
        f"{relative}: expected exactly one upstream open_adapter surface-mode anchor; "
        f"found {source.count(old)}; refusing to modify unknown source"
    )
