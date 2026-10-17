# Glossary

[简体中文](glossary.md) · [Wiki](README.en.md)

| Term | Meaning here |
| --- | --- |
| HWC | Vendor Hardware Composer handling input, mode decisions, refresh processing, and scan consumption |
| GPU helper | `libeinkgpu.so`, computation on matched paths; loaded does not mean active |
| Mode | External refresh request and internal decision, such as Clear, Reading, Fast, Extreme |
| Quantization / dither | Mapping continuous grayscale to finite levels or binary pixels; separate from waveforms |
| Bayer | Fixed spatial threshold matrix; grayscale and binary have separate implementations |
| Blue noise | Fixed noise thresholds distributing visible quantization structure |
| Error diffusion | Propagating quantization error into later pixels, creating spatial dependencies |
| Nearest | 16-level point quantization without spatial error propagation |
| Hysteresis | Previous-frame stabilization near thresholds; additional switch supports selected binary algorithms |
| WBF / waveform | Drive decisions from old/new pixels, temperature, and phase; not a screenshot |
| Wave / scan frame | A group of drive computations / one scan within it; neither directly means FPS |
| Fence | GPU/buffer synchronization point; waiting and computation times differ |
| HOT / REBOOT | Hot application / full-restart configuration categories |
| Revision | Monotonic configuration snapshot identity for acknowledgement |
| PID + starttime | Process identity resistant to PID reuse |
| boot_id | Boot identity preventing stale startup acknowledgement |
| Conditional restoration | Restore only values still owned by the module |
| CPU fallback | Stock CPU processing after unsupported paths or faults; slowness alone does not prove it |
| Standalone-kernel validation | Fixed-input computation comparison, without panel or full HWC acceptance |
| Candidate / prerelease | Development build requiring scoped device acceptance beyond cloud checks |
