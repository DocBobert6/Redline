# Third-party software and data

| Component | License | Where | Use |
| --- | --- | --- | --- |
| [androidx.heifwriter](https://developer.android.com/jetpack/androidx/releases/heifwriter) | Apache-2.0 | shipped in the app (B25) | HEIF stills through the phone's HEIC / HEVC encoder |
| [libultrahdr](https://github.com/google/libultrahdr) (v1.4.0) | Apache-2.0 | CI only, not part of the app (B25) | Host validation of the Ultra HDR JPEGs the emulator test writes (`ci/uhdr_validate.cpp`); the app itself encodes Ultra HDR with the platform encoder (`Bitmap.setGainmap`, API 34+) and computes the gain map itself |
| [lensfun](https://lensfun.github.io/) database | CC BY-SA 3.0 | calibration toolkit (data not committed) | RF 24-70 distortion and vignetting coefficients (B04, B24) |

Copyright (C) Google LLC, the Android Open Source Project, for libultrahdr and androidx.heifwriter: licensed under the Apache
License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
