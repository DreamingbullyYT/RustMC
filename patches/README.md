# Android patch series

Place reviewed, ordered Git-compatible patch files here using the `.patch`
extension. They are applied to the checked-out Horizon submodule after every
upstream synchronization and before Android builds/tests.

Keep Android application code in `android/` whenever possible. Use patches only
when an Android integration point cannot be implemented outside upstream.

A patch that stops applying must fail the synchronization job. Do not add
`--reject`, ignore errors, or automatically discard a patch.
