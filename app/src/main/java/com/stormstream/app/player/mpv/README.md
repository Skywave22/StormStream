# libmpv / MPV integration

Drop prebuilt `libmpv.so` native libraries for each target ABI under
`app/src/main/jniLibs/<abi>/libmpv.so` (e.g. `arm64-v8a/libmpv.so`) and the
player will automatically use libmpv as the primary engine.  When libmpv is not
bundled (as in CI debug builds), StormStream automatically falls back to
ExoPlayer via `PlayerEngine.create()`.
