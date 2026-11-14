HandBrake 1.11.2 motion-metric backports
======================================

These are unmodified upstream patches, applied in this order with zero fuzz:

1. [c81f57c](https://github.com/HandBrake/HandBrake/commit/c81f57ce59d65bbfce94a3473bc53e224c9350cd)
   removes the approximation path, which upstream identifies as capable of an
   out-of-bounds read.
2. [c23c4f7 / PR 8198](https://github.com/HandBrake/HandBrake/pull/8198)
   handles shifted samples and removes the ARM64 NEON motion-metric path.

The stock 1.11.2 ARM64 Linux build reproducibly crashes on the generated
1280x720 10-bit HEVC/ASS fixture with the production 29.97 peak-frame-rate
settings. A symbolized backtrace identifies `motion_metric_16` via
`adjust_frame_rate` and `hb_vfr_work`. The old NEON routine treats byte strides
as 16-bit sample strides. Disabling x265 assembly does not address this filter.

Keep the native fixture in `container-media-matrix.sh` as a regression check.
The image labels record both commit IDs, and the runtime ships these patches
beside the HandBrake license. Remove the backports when a tested stable release
includes both fixes. The reported upstream version remains 1.11.2; describe the
container toolchain as **1.11.2 with motion-metric backports**, not stock 1.11.2.
