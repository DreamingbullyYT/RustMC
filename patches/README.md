# Minimal upstream patch series

The upstream engine is fetched at build time from the exact revision in
`UPSTREAM.lock`. Ordered `.patch` files in this directory are applied to the
temporary checkout by `scripts/checkout-upstream.sh`.

Keep Android application code in separate modules. A patch is allowed only
when the required extension point cannot be implemented outside upstream.
Every patch must apply cleanly to the pinned commit and must be tested against
the desktop path and Android target. Do not silently skip failed patches.
