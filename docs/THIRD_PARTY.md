# Third-party software and data

| Component | License | Where | Use |
| --- | --- | --- | --- |
| [libultrahdr](https://github.com/google/libultrahdr) (v1.4.0) | Apache-2.0 | CI only, not part of the app (B25) | Host validation of the Ultra HDR JPEGs the emulator test writes (`ci/uhdr_validate.cpp`); the app itself encodes Ultra HDR with the platform encoder (`Bitmap.setGainmap`, API 34+) and computes the gain map itself |
| androidx.heifwriter (reference only) | Apache-2.0 | not shipped since B30 | B25 used it for HEIF; B30's own writer (`PhotoHeif`) follows its HEIF track layout (grid keys, Exif sample) |
| [lensfun](https://lensfun.github.io/) database | CC BY-SA 3.0 | calibration toolkit (data not committed) | RF 24-70 distortion and vignetting coefficients (B04, B24) |

Copyright (C) Google LLC, the Android Open Source Project, for libultrahdr: licensed under the Apache
License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
