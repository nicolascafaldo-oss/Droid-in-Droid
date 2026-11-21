# Kestrel Sandbox

This repository contains the Rust and C++ components for the Kestrel sandbox engine.

## Building

```bash
# Rust side
cd native/kestrel_sandbox_rust && cargo build

# C++ side
cd native/kestrel_hal_cpp && mkdir build && cd build && cmake .. && make
```

## Testing

```bash
# Rust tests
cargo test --workspace --all-targets

# JNI tests
# (Run via gradle from the app module)
./gradlew connectedAndroidTest
```

## CI

A CI workflow is defined in `.github/workflows/ci.yml`.

![CI](<CI badge URL>)
