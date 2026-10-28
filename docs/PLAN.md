```text
REDLINE - PRODUCT & ENGINEERING PLAN
Last updated: October 28, 2026
Target look: Canon EOS R5 Mark II + Canon RF 24-70mm f/2.8L IS USM
Reference tier: the Baseline device profile (Section 3)

Key principles:
 - THE DEFAULT CONTRACT (Section 3): every setting that ships ON by
   default must work on the Baseline device profile (a Camera2 FULL
   phone with RAW, 12 MP max output, 8-bit video, no telephoto). No default may depend on hardware the baseline lacks.
 - FLAGSHIP CEILING (Section 4): the app still exposes the best
   capabilities of today's top Android phones (Galaxy S26 Ultra,
   Pixel 11 Pro, Vivo/Oppo/Xiaomi Ultras) whenever a device has them.
 - Measured baseline Camera2 data, focal-length math, crop/resolution
   table, performance budgets, and a baseline-gated roadmap.
 - Look data (color, tone, lensfun distortion/vignetting, noise, DOF
   tables) lives in Sections 2, 7, 9, 19.
Tags: [MEASURED] lab/device data, use as-is. [PUBLISHED] spec sheets
and reviews. [ESTIMATE] reasoned guess, with how to verify it.
[BASELINE-MEASURED] read from the maintainer's baseline device Camera2 report.
[BASELINE-VERIFY] must be confirmed in code on the baseline device in Phase 1.

======================================================================
1. FEASIBILITY VERDICT
======================================================================
YES. The baseline profile is workable: its main camera is Camera2 FULL with
RAW, manual sensor control, manual post-processing (custom ISP tone
curves), burst capture, OIS + EIS and a true 24p frame range. Those are
exactly the hooks the Canon look needs.

What the baseline device limits (and how the plan handles it):
 - 12 MP max output to third-party apps (no 48 MP readout). 50mm and
   70mm framings are crops, so default stills use multi-frame
   super-resolution, and 50/70mm default to a 6 MP output size.
 - 8-bit video only (no 10-bit / HLG / log). Default video looks are
   designed for 8-bit; log and HDR video are flagship unlocks.
 - Phone main lens is ~26mm equivalent across the 3:2 frame, so the
   "24" stop is ~26mm on the baseline device unless the user opts into the
   ultrawide-based true-24 path.
 - Small 1/2" sensor at f/1.7 (~f/9 full-frame equivalent): natural
   background blur is ~3.3x less than f/2.8 full frame, so simulated
   depth of field carries more of the look.

Realistic target unchanged: a photographer glancing at the image says
"that looks like a Canon L-zoom shot", in good light, at any stop.

======================================================================
2. THE TARGET: WHAT MAKES THE LOOK (RESEARCH SUMMARY)
======================================================================
2.1 Canon EOS R5 Mark II (body)
- Sensor: 36 x 24 mm full-frame, back-illuminated STACKED CMOS,
  ~45 MP effective (50.3 MP total). Native 3:2 aspect ratio.
- Max still resolution: 8192 x 5464 (JPEG L / RAW / C-RAW / HEIF);
  M = 6000 x 4000; S1 = 4176 x 2784.
- Processing: DIGIC X + new DIGIC Accelerator co-processor.
- File types: 14-bit RAW, 14-bit C-RAW, 10-bit HEIF, 8-bit JPEG.
- ISO: 100-51,200 native (50-102,400 expanded).
- Color spaces: sRGB / Adobe RGB (SDR); BT.2020 for HDR PQ.
- Picture Styles: Auto, Standard (default), Portrait, Landscape, Fine
  Detail, Neutral, Faithful, Monochrome, 3 User Defined.
  * Standard: sharpness 3 (scale 0-7), boosted saturation and contrast,
    "vivid, sharp, crisp"; skin slightly warm/pinkish, Canon's signature
    smooth reds/magentas in skin.
  * Portrait: rosier skin, softer sharpness (2).
  * Landscape: deeper blues/greens.
- In-camera tone tools: Highlight Tone Priority (3 settings), Auto
  Lighting Optimizer (4 settings).
- Video: 8K up to 60p RAW, 4K up to 120p, 10-bit 4:2:2 internal.
  Video looks: Canon 709 (film-like, skin-tone-focused, soft highlight
  roll-off), Canon Log 2 (widest DR), Canon Log 3, PQ, HLG,
  BT.709 Standard, and Picture Styles (match stills color).
- Character of "the Canon look" in general: contrasty gamma straight
  out of camera, warm-leaning neutrals, flattering skin, clean noise,
  smooth highlight roll-off, moderate (not crunchy) sharpening.

2.2 Canon RF 24-70mm f/2.8L IS USM (lens)
- 21 elements / 15 groups; 3 glass-molded aspherical, 3 UD elements.
- Constant f/2.8, min f/22. 9 CIRCULAR aperture blades (round bokeh,
  round highlights even stopped down a little).
- Angle of view (diagonal): 84 deg at 24mm to 34 deg at 70mm.
  Horizontal 74-29 deg, vertical 53-19.5 deg.
- Closest focus 0.21 m (24mm) / 0.38 m (70mm); 0.30x max magnification
  at 32mm.
- Coatings: Air Sphere Coating (ASC) - low flare/ghosting; flare grows
  stopped down (f/16) with sun in frame.
- Distortion (uncorrected RAW): complex "mustache" barrel at 24mm,
  near zero ~35mm, mild pincushion toward 70mm (about 1.5% at 70mm).
  In-camera JPEGs are corrected -> very low distortion.
- Vignetting (uncorrected): HIGH at 24mm f/2.8, still visible at f/8 at
  24mm; less at longer focal lengths but noticeable wide open. Mostly
  gone by f/4 at 35-70mm. In-camera JPEGs partially correct it
  (Peripheral Illumination Correction), so a SOFT residual falloff
  remains in typical images.
- Chromatic aberration: near zero (lab-measured); slight fringing in
  out-of-focus areas at 70mm (longitudinal CA / "bokeh fringing").
- Sharpness: very high, edge-to-edge, from wide open to ~f/11.
- Bokeh: smooth, round, slight cat's-eye toward corners wide open.

2.3 What we must reproduce (priority order for "looks like it")
 1. Color science + tone curve (Picture Style Standard)   - biggest win
 2. Framing: 3:2 aspect + 24/35/50/70mm field of view    - big win
 3. Highlight roll-off and contrast                      - big win
 4. Subject separation (simulated f/2.8 full-frame DOF)   - big win, hardest
 5. Residual vignetting per focal length/aperture         - subtle, important
 6. Sharpening style (Canon "3" not phone over-sharpening) - subtle
 7. Noise/grain character per ISO                         - subtle
 8. Distortion signature (mostly corrected)               - very subtle
 9. Bokeh shape (round, 9-blade, cat's-eye at edges)       - in bokeh only
10. Flare (low, ASC-clean)                                - rare

======================================================================
3. THE DEFAULT CONTRACT - BASELINE DEVICE PROFILE
======================================================================
3.1 Rule
 - Every feature or setting that is ON by default (first launch, any
   device) must have a working, tested implementation on the baseline device.
 - A default may take a better hardware path on a stronger phone
   ("adaptive default"), e.g. the 70mm stop uses a native telephoto on
   a Galaxy S26 Ultra but a super-res crop on the baseline device. Same setting,
   same look, better source. That is allowed.
 - Anything the baseline device cannot do (10-bit, log, 48-200 MP, 8K, APV, 4K120,
   depth camera, etc.) is OFF by default and appears only on devices
   that pass detection AND a live session test.
 - Release gate: no build ships unless the full default configuration
   passes the automated baseline test suite (Section 14).

3.2 Baseline measured capabilities [BASELINE-MEASURED, Oct 28, 2026]
 Back, logical camera 0 (physical cameras 2 = main, 3 = ultrawide):
 - Hardware level FULL (not LEVEL_3).
 - Capabilities: BACKWARD_COMPATIBLE, MANUAL_SENSOR,
   READ_SENSOR_SETTINGS, MANUAL_POST_PROCESSING, BURST_CAPTURE,
   STREAM_USE_CASE, RAW, CONSTRAINED_HIGH_SPEED_VIDEO,
   LOGICAL_MULTI_CAMERA.
 - NOT present: DYNAMIC_RANGE_TEN_BIT, ULTRA_HIGH_RESOLUTION_SENSOR,
   YUV/PRIVATE_REPROCESSING, DEPTH_OUTPUT.
 - Max JPEG/YUV/PRIVATE size 4000x3000 (4:3). Other sizes include
   3840x2160, 4000x2000, 3264x2448. No 3:2 size above 720x480.
 - FPS ranges: [15,15] [15,24] [24,24] [15,30] [24,30] [30,30] [15,60]
   [60,60].
 - AE: manual + auto + flash modes (no red-eye); AE lock; exposure
   compensation -24..+24 at 1/6 EV (= +/-4 EV).
 - AF: manual, auto, continuous picture, continuous video, macro, EDOF.
 - AWB: off, auto, all presets; AWB lock.
 - Stabilization: OIS + video EIS.
 Front, camera 1: FULL, RAW, MANUAL_SENSOR, MANUAL_POST_PROCESSING,
   BURST, fixed focus, no OIS (EIS only), up to 4208x3072.
 Hardware [PUBLISHED]: mid-range SoC, 8 GB RAM; main
   48 MP Quad-PD 1/2" 0.8 um f/1.7, 82 deg diagonal FOV (4:3);
   ultrawide 13 MP 1/3.1" f/2.2 120 deg fixed focus; no telephoto;
   video up to 4K60 on main. Android 16+.
 [BASELINE-VERIFY] in Phase 1: RAW_SENSOR size and max RAW burst rate; HEVC
   Main10 encoder; JPEG_R / HEIC / HEIC_ULTRAHDR stream support;
   LENS_DISTORTION + LENS_INTRINSIC_CALIBRATION presence; TONEMAP max
   curve points; Low Light Boost AE mode; COLOR_CORRECTION_MODE_CCT;
   preview stabilization; night extension; exact stream combinations
   for RAW + YUV + PRIVATE at 4000x3000.

3.3 Focal-length math on the baseline device [ESTIMATE from 82 deg spec;
    confirm with LENS_INFO_AVAILABLE_FOCAL_LENGTHS and
    SENSOR_INFO_PHYSICAL_SIZE]
 - Main camera horizontal FOV ~69.5 deg -> ~26mm equivalent across the
   width of a 3:2 frame. The RF 24-70 at 24mm is 73.7 deg horizontal.
 - 3:2 crop from 4000x3000 = 4000x2667 (10.7 MP) at ~26mm.
   Crop to 35mm: 1.35x -> ~2960x1973 (5.8 MP)
   Crop to 50mm: 1.93x -> ~2070x1380 (2.9 MP)
   Crop to 70mm: 2.70x -> ~1480x987  (1.5 MP)
 - Super-resolution burst (Section 6) adds real detail, roughly 1.5-2x
   linear [ESTIMATE; Pixel-style handheld super-res], so 50mm reaches
   ~6-9 MP of real detail and 70mm ~3-5 MP.
 - Ultrawide as a "true 24": 120 deg diagonal crop to 73.7 deg
   horizontal leaves ~3.8 MP from a fixed-focus 1/3.1" sensor. Worse
   than main at 26mm, so it is opt-in only on the baseline device.

======================================================================
4. CAPABILITY DETECTION + THE FLAGSHIP CEILING
======================================================================
4.1 DeviceProfile (built at first launch, cached, refreshed on OS update)
- CameraManager.getCameraIdList() + getPhysicalCameraIds() of each
  logical camera; open physical streams where the HAL allows.
- LENS_FACING; INFO_SUPPORTED_HARDWARE_LEVEL; REQUEST_AVAILABLE_
  CAPABILITIES (RAW, MANUAL_SENSOR, MANUAL_POST_PROCESSING,
  LOGICAL_MULTI_CAMERA, DEPTH_OUTPUT, DYNAMIC_RANGE_TEN_BIT,
  BURST_CAPTURE, YUV/PRIVATE_REPROCESSING, ULTRA_HIGH_RESOLUTION_SENSOR,
  STREAM_USE_CASE, COLOR_SPACE_PROFILES).
- Equivalent focal length per physical lens:
  eq = f * 43.27 / sensor_diagonal_mm, plus horizontal-FOV-based eq for
  3:2 framing (Section 3.3 method).
- Apertures, min focus distance, ISO and shutter ranges.
- SCALER_STREAM_CONFIGURATION_MAP (+ _MAXIMUM_RESOLUTION map on UHR
  sensors), min frame / stall durations.
- Mandatory stream combinations; on API 35+ use
  CameraDevice.CameraDeviceSetup.isSessionConfigurationSupported() and
  CameraX 1.5 Feature Group API (GroupableFeature: HDR_HLG10, FPS_60,
  PREVIEW_STABILIZATION, IMAGE_ULTRA_HDR) to verify COMBINATIONS, not
  just single features.
- Color: SENSOR_COLOR_TRANSFORM1/2, FORWARD_MATRIX1/2,
  CALIBRATION_TRANSFORM, REFERENCE_ILLUMINANT1/2; LENS_DISTORTION and
  LENS_INTRINSIC_CALIBRATION.
- DynamicRangeProfiles (HLG10, HDR10, HDR10+, Dolby Vision).
- ImageFormat support: RAW_SENSOR, JPEG_R (Ultra HDR), HEIC,
  HEIC_ULTRAHDR (Android 16+).
- CameraExtensionCharacteristics (Night, Bokeh, HDR) and
  EXTENSION_NIGHT_MODE_INDICATOR (Android 16+).
- Android 15+: CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY.
- Android 16+: COLOR_CORRECTION_MODE_CCT (Kelvin + tint WB).
- Encoders: MediaCodecList for HEVC Main10, AV1, APV (Android 16+).

4.2 First-launch self-test (protects the default contract on ALL phones)
- After building the profile, silently open each default session
  (preview + still RAW burst + video) for ~1 s each, capture a few
  frames, check frame rate and that results carry expected metadata.
- Any failure demotes that feature to the next safe path and is logged
  (anonymous) to the remote quirks table.
- Remote quirks table (JSON via remote config) overrides detection for
  known-bad HALs.

4.3 Capability tiers
 Tier Basic  : LEGACY/LIMITED or no RAW. Processed YUV path, look LUT on
               top, label "Standard".
 Tier Pro    : FULL + RAW + MANUAL_SENSOR. THE BASELINE TIER. All
               defaults are designed here.
 Tier Pro+   : anything above, auto-detected unlocks (4.4).

4.4 Flagship unlocks (OFF by default unless noted "adaptive default")
 Feature                         Detect via                   baseline?
 Native tele for 50-70mm stop    physical lens eq 60-80mm     no  (adaptive default)
   e.g. S26 Ultra 3x 67mm
 High-res source for crops       ULTRA_HIGH_RESOLUTION_SENSOR no  (adaptive default)
   48-200 MP (S26 Ultra 200 MP,  + max-res stream map
   Pixel 11 Pro 50 MP)
 True 24mm from main             main eq <= 24mm horizontal   no  (adaptive default)
 Large output (8192x5464 "L")    UHR + memory check           no
 10-bit HLG video + HEVC Main10  DYNAMIC_RANGE_TEN_BIT        no
 "C-Log-style" log video         10-bit required              no
 HDR10 / HDR10+ / Dolby Vision   DynamicRangeProfiles         no
 APV codec (pro intermediate)    MediaCodecList, Android 16+  no  [BASELINE-VERIFY]
 4K120 / 8K24-30                 high-speed / size map        no
 LEVEL_3 RAW+YUV reprocessing    REPROCESSING caps            no
 Hardware depth / ToF            DEPTH_OUTPUT                 no
 Native Ultra HDR JPEG_R stream  ImageFormat.JPEG_R           [BASELINE-VERIFY]
   (otherwise software gainmap, which IS default on the baseline device)
 Low Light Boost AE              AE mode list (Android 15+)     [BASELINE-VERIFY]
 Kelvin/tint WB in hardware      COLOR_CORRECTION_MODE_CCT    [BASELINE-VERIFY]
   (otherwise software Kelvin WB on RAW, which IS default)
 Portrait (bokeh) video 4K       GPU benchmark pass           no; baseline gets 1080p30 opt-in if benchmark passes

======================================================================
5. DEFAULT SETTINGS SPEC (WHAT SHIPS ON, AND HOW THE BASELINE DOES IT)
======================================================================
 Setting              Default                    Baseline path                    Flagship path
 Aspect ratio         3:2                        crop 4000x3000 -> 4000x2667      same / larger source
 Zoom ring            24-28-35-50-70 stops,      main cam crops; "24" = ~26mm     native lenses where they
                      opens at 35mm              (labelled "24*" with note)       match; true 24
 Picture Style        Standard                   RAW pipeline + fitted LUT        same
 Virtual aperture     f/2.8 (drives DOF +        ML depth + segmentation          + depth camera / dual-cam
                      vignette)                                                   disparity
 Simulated DOF        ON for stills (auto-off    depth confidence gate            same
                      when depth confidence low)
 Still capture        RAW burst merge            8-12 RAW frames, handheld        UHR or tele RAW source
                      (HDR + noise + super-res)  super-res at 50/70
 Output size          24/35: 4000x2667 (10.7MP)  as listed                        up to 8192x5464 when UHR
                      50/70: 3000x2000 (6 MP)
 File format          JPEG with Ultra HDR        libultrahdr software gainmap     JPEG_R/HEIC_ULTRAHDR
                      gainmap (SDR base = Canon  from our merged HDR              native if present
                      Standard look)
 RAW file (DNG)       OFF (toggle)               DngCreator, merged or single     same
 White balance        Auto, Canon "ambience      AWB + our tungsten retention     + hardware CCT
                      priority" warmth           (19.3)
 Highlight tone       Soft shoulder (HTP-like)   bracketed-under burst            same
 Sharpening           Canon Standard ~4/7        EDGE_MODE OFF/minimal + our USM  same
 Noise reduction      Canon-like fine grain      NOISE_REDUCTION minimal + merge  same
 Vignetting           In-camera residual (~0.3x  linear-light radial map          same
                      of measured falloff)
 Distortion           Corrected (near zero)      undo phone distortion            same
 Stabilization        OIS on (stills), OIS+EIS   OIS + VIDEO_STABILIZATION        + preview stabilization
                      (video)                                                     where groupable
 Video                4K 30p, 8-bit HEVC,        ISP flat tonemap curve ->        10-bit HLG pipeline,
                      "Canon 709-style" look     GPU LUT chain -> HEVC            log, 4K60/120, 8K, APV
 24p cinematic        available (not default)    [24,24] FPS range                same
 Video bokeh          OFF                        opt-in 1080p30 if benchmark      4K30 on top GPUs
 Grid/level/histogram ON (level, histogram)      UI only                          same
 Pro tools            focus peaking, zebras      GPU overlays on preview          + waveform/false color
                      available, off             (all baseline-capable)           same
 Location/EXIF        EXIF with Look tag; no     ExifInterface                    same
                      Canon make/model spoof

======================================================================
6. IMAGE PIPELINE (STILLS)
======================================================================
Pro path (baseline device and up):
 1. On shutter: RAW_SENSOR burst, 8-12 frames (count by light level),
    exposure ~0.7-1 EV under for highlight headroom (HTP-like).
    Zero-shutter-lag ring buffer of RAW frames where the stream combo
    allows [BASELINE-VERIFY RAW rate]; else capture-on-press.
 2. Align + merge (tile-based, HDR+-style) to one linear RAW with
    lower noise and higher DR. At 50/70mm, merge onto a finer grid
    using natural hand shake + OIS motion (handheld multi-frame
    super-resolution, Wronski et al. 2019).
 3. Black/white level, lens shading (phone's own) correction, demosaic
    (skipped at super-res stops; the merge reconstructs RGB directly).
 4. Camera RGB -> XYZ via our ColorChecker-fitted baseline matrices (9.6),
    falling back to the HAL's SENSOR_COLOR_TRANSFORM on other phones;
    chromatic adaptation to scene white with Canon-style warmth
    retention.
 5. XYZ -> Canon Standard via fitted tone curve + 33^3 LUT (9.1).
 6. Highlight shoulder / optional ALO-like local lift.
 7. Lens emulation (Section 7): crop to stop, distortion (default off),
    vignetting, simulated DOF + 9-blade bokeh, bokeh-edge CA at 70mm.
 8. Canon-style sharpening; grain matched to R5 II at equivalent ISO.
 9. 3:2 output at the Section 5 size; JPEG + Ultra HDR gainmap (the
    gainmap is computed from the merged HDR linear image so bright
    highlights render on HDR screens); optional DNG; EXIF incl. custom
    "Look: Redline R5II/RF24-70 @ 35mm f/2.8" tag.
Timing target on baseline: shutter-to-thumbnail < 0.3 s (preview frame with
look), full-quality save < 3 s at 24/35mm, < 5 s at 50/70mm, all in a
background queue so shooting never blocks.
Basic path (no RAW): YUV with NR/EDGE minimal, estimated inverse OEM
tone curve, then steps 5-9 (labelled "Standard").

======================================================================
7. LENS EMULATION - RF 24-70mm f/2.8L (DETAILED)
======================================================================
- Virtual zoom ring with click stops 24 / 28 / 35 / 50 / 70.
- Diagonal AoV targets: 24mm 84 deg, 35mm 63 deg, 50mm 47 deg, 70mm 34
  deg. Crop phone frame to match, in 3:2.

7.2 Distortion signature
- First remove phone lens distortion (LENS_DISTORTION coefficients).
- Default: near-zero residual (R5 II JPEGs are distortion-corrected
  for RF lenses). Optional "RAW feel" toggle applies the measured
  lensfun ptlens model (Section 19.1): mustache barrel ~-1.2% at 24mm
  long edge, ~0 at 31mm, rising pincushion to ~+1.0% edge / +1.6%
  corner at 58-70mm. Interpolate coefficients by focal length.

7.3 Vignetting
- Radial falloff map per (focal, aperture) from the lensfun pa model
  (Section 19.2). Uncorrected corner falloff is large: -2.2 EV at
  24mm f/2.8, -1.5 to -1.7 EV at 31-70mm f/2.8.
- Default ("in-camera JPEG") = residual after Canon's Peripheral
  Illumination Correction. [ESTIMATE] scale the measured falloff (in
  EV) by ~0.3 => ~-0.6 EV corners at 24/2.8, ~-0.5 EV at 50-70/2.8,
  ~-0.3 EV at f/4. Refine by comparing sample JPEG corners vs RAW
  corners in the 9.1 pairs (MakerNotes record whether correction
  was on).
- Apply in linear light BEFORE tone curve, after phone shading
  correction removed phone's own falloff.

7.4 Depth of field / bokeh (the hardest part)
- Virtual aperture f/2.8 - f/22.
- Depth sources, best first: DEPTH_OUTPUT camera / dual-camera
  disparity -> ML monocular depth (e.g. Depth Anything V2 small,
  MiDaS) via TFLite/LiteRT GPU delegate -> person segmentation
  (MediaPipe) as fallback.
- Compute circle-of-confusion per pixel from a THIN-LENS model of a
  full-frame camera at the chosen focal/aperture and the focus
  distance (from AF or tap): CoC = (f^2/(N*(s-f))) * |d-s|/d,
  then scale to image pixels using 36mm sensor width.
- Render bokeh as a scatter/gather blur in linear light with a
  9-blade ROUNDED aperture kernel; highlights stay bright (render
  in linear/HDR so specular highlights bloom into disks).
- Cat's-eye vignetting of bokeh disks toward frame edges at f/2.8,
  becoming round when stopped down.
- Slight green/magenta fringing on out-of-focus edges at 70mm only.
- Edge refinement (hair, glasses) via guided filter on the matte.
- Don't apply when depth confidence is low; fall back gracefully.

7.5 Flare
- Default off/minimal (ASC lens is clean). Optional subtle veiling
  glare when a light source is in frame, stronger at f/16.
7.6 Baseline device notes
- The "24" stop on the baseline device renders at ~26mm (main camera's full width,
  Section 3.3) and is labelled "24*". Opt-in "True 24 (ultrawide)"
  uses camera 3 at ~3.8 MP, fixed focus.
- Phone depth of field at f/1.7 on a 1/2" sensor ~ f/9 full-frame
  equivalent: the simulation must add ~3.3x the blur the phone gives
  (vs ~2.1x on a 1/1.3" flagship main).
- No depth camera: depth = Depth Anything V2 small (LiteRT GPU) +
  MediaPipe person segmentation + guided-filter edge refinement.
  Dual-camera disparity from main + ultrawide is a later experiment
  [BASELINE-VERIFY concurrent physical streams].

======================================================================
8. VIDEO PIPELINE
======================================================================
8.1 Baseline default (8-bit)
- Camera2 session: PRIVATE stream at 3840x2160, FPS [30,30] (24p
  option uses [24,24]), OIS + VIDEO_STABILIZATION_MODE_ON.
- Use MANUAL_POST_PROCESSING to load a FLAT custom TONEMAP curve
  (log-like, CONTRAST_CURVE mode) and minimal NR/EDGE in the ISP. This
  keeps highlight and shadow information inside the 8-bit stream.
  [BASELINE-VERIFY curve point count and that the curve applies to the
  PRIVATE stream.]
- GPU (OpenGL ES 3.2, 16-bit float textures): invert our flat curve ->
  color matrix -> 33^3 LUT (Canon 709-style or Standard) -> crop to
  stop -> vignette -> light grain -> encoder surface.
- Encode HEVC at ~50-80 Mbps 4K30. If the baseline device has an HEVC Main10
  encoder, encode the GPU output in 10-bit to avoid banding from the
  LUT even though the sensor input is 8-bit (optional, [BASELINE-VERIFY]).
- Looks on baseline: "Canon 709-style" (default), "Standard" (matches
  stills). No log on 8-bit devices (it bands).
- Budget: chain <= 12 ms per 4K frame on the baseline GPU [ESTIMATE; measure].
  4K60 offered if the benchmark passes; slow-motion via the
  constrained high-speed session at sizes the device lists.
- Video bokeh: opt-in, 1080p30, depth at reduced resolution with
  temporal smoothing; disabled if thermal status >= MODERATE.
8.2 Flagship unlocks
- 10-bit HLG10 + HEVC Main10; Redline "C-Log-style" log with matching
  downloadable .cube LUTs; HDR10/HDR10+/Dolby Vision where listed;
  APV codec for editing workflows; 4K120 / 8K; video bokeh 4K30.
- Verified as feature groups (HLG10 + FPS_60 + PREVIEW_STABILIZATION)
  before being offered together.

======================================================================
9. CALIBRATION WITHOUT THE CAMERA
======================================================================
plus one ~$100 chart for your own phones.

9.1 Paired RAW + in-camera JPEG files (the color/tone "answer key")
- Sources: DPReview R5 II sample gallery (42 images, Sep 12, 2024;
  RAW + out-of-camera JPEG, Standard style) and pre-production
  gallery (68 images); DPReview studio comparison scene (same
  ColorChecker-style scene shot across every camera, RAW + JPEG at
  each ISO); digitalkamera.de R5 II + RF 24-70 f/2.8 test pack (RAW +
  JPEG at all 12 ISO steps, about EUR 0.99); Sam Hurd's R5 II review
  RAWs; Matt Granger R5 II sample files; raw.pixls.us.
- Filter to files whose EXIF/MakerNotes say Picture Style = Standard,
  default parameters, Auto Lighting Optimizer state known. exiftool
  reads Canon MakerNotes: Picture Style, Sharpness Strength/
  Fineness/Threshold, Contrast, Saturation, Color Tone, ALO, HTP,
  lens corrections on/off, WB multipliers. That alone pins down
  Canon's real default parameters for this body.
- Pipeline (Python, rawpy/LibRaw supports R5 II CR3):
  CR3 -> demosaic linear -> apply WB from MakerNotes -> camera RGB
  -> XYZ using the R5 II matrix (9.3) -> pixel-aligned pairing with
  the JPEG (they come from the same exposure, so alignment is just
  undoing in-camera distortion correction) -> fit:
    a) 1D tone curve (luminance, robust median per bin),
    b) 3D residual LUT 33^3 in a perceptual space (e.g. linear ProPhoto
       or Oklab), regularized/smoothed so sparse colors interpolate.
  Millions of pixel pairs across ~100 images give a far denser fit
  than one ColorChecker would.
- This yields "scene-linear XYZ -> Canon Standard sRGB JPEG", which
  is device-independent. Your phone side only has to produce good
  scene-linear XYZ.

9.2 Adobe "Camera Standard" DCP for the R5 II (cross-check)
- Free Adobe DNG Converter installs Canon EOS R5 Mark II Camera
  Standard / Portrait / Landscape / Neutral / Faithful .dcp profiles.
  Adobe built these specifically to mimic Canon's in-camera styles.
  Each contains ForwardMatrix, HueSatMap, LookTable and a tone curve.
- Sample one into a 33^3 CLUT with camicc or dcamprof and compare to
  your 9.1 fit. Where they agree, high confidence; where they differ,
  trust the JPEG pairs. Use the DCP as an internal reference only -
  do not ship Adobe's profile data (license).

9.3 Sensor color matrix [MEASURED - LibRaw/Adobe coefficients]
- R5 II ColorMatrix (XYZ D65 -> camera RGB, values /10000):
     9396  -2598  -1207
    -4408  12296   2369
     -505   1575   6077
  (Original R5 for comparison: 9766 -2953 -1254 / -4276 12116 2433 /
  -437 1336 5131.) Inverting this gives the camera->XYZ step for the
  9.1 fit.

9.4 Lens data [MEASURED - lensfun database, "Canon RF 24-70mm F2.8L
IS USM"; distortion measured on EOS RP, vignetting on EOS R6 Mark II -
same lens, same full-frame RF mount, so it transfers directly to R5 II]
- Full coefficient sets are in Section 19. Use the lensfun library
  (C, or the pure-Rust port lensfun-rs) to apply them in reverse,
  i.e. ADD the distortion/vignetting instead of removing it.

9.5 Sensor noise [MEASURED - Photons to Photos, Bill Claff]
- See Section 19.4. Use to synthesize matching grain per ISO.

9.6 Your phones (the only thing you must measure yourself)
- Buy a ColorChecker Classic (~$100) or Passport. Shoot it in RAW on
  each test phone under daylight, shade, and tungsten; fit phone
  camera RGB -> XYZ (3x3 per illuminant + small residual LUT).
  Also shoot a white wall at each lens to measure phone vignetting
  and a grid/door frame for distortion (or trust LENS_DISTORTION
  metadata).
- Chain: phone RAW -> phone->XYZ (9.6) -> XYZ->Canon Standard
  (9.1/9.2) -> lens model (9.4) -> grain (9.5).

9.7 Validation without the camera
- Hold out 20% of the RAW+JPEG pairs from the fit and check error.
- Then validate on real scenes: photograph similar subjects (skin, sky, foliage, neon, tungsten
  interiors) and do blind A/B against R5 II sample JPEGs with 10+
  photographers on a forum (r/Canon, DPReview).
- Numeric target: the XYZ->Standard LUT reproduces the paired JPEGs
  at mean deltaE2000 < 2.5 (skin < 2).

Legal note: Do NOT ship Canon .pf2 Picture Style files, Canon LUTs,
or Adobe DCP data. Ship only LUTs you fitted yourself. Avoid Canon
trademarks in branding unless licensed.
9.8 Baseline device calibration (Phase 0, maintainer task)
- ColorChecker Classic: shoot RAW (DNG) on the baseline device main and front
  cameras (ultrawide optional) under daylight, open shade, tungsten,
  and one LED; fit camera RGB -> XYZ per illuminant + small residual
  LUT; target mean deltaE2000 < 2.0 on the chart.
- Flat-field: evenly lit white wall/defocused screen at each camera to
  measure the baseline device's own shading after HAL correction.
- Distortion: read LENS_DISTORTION; if absent, shoot a grid.
- Noise: dark frames + flat frames at ISO 50/100/200/400/800/1600/3200
  for a Poisson-Gaussian noise model (used for merge weights and grain
  matching).
- Save as calibration/devices/<device>.json; same procedure for any
  other phone that becomes a test device.

======================================================================
10. TECH STACK
======================================================================
- Kotlin + Jetpack Compose. minSdk 29 (Android 10); targetSdk = latest
  (Android 17, API 37). 10-bit, CCT WB, HEIC_ULTRAHDR, APV etc. gated
  by API level at runtime.
- Camera2 core for capture control; CameraX 1.5+ (Camera2Interop) for
  lifecycle and the Feature Group / feature-combination query API.
- Native C++ (NDK): RAW align/merge/super-res (Halide recommended),
  demosaic; Vulkan compute or GLES 3.2 for LUT, bokeh, grain.
- ML: LiteRT GPU delegate (Depth Anything V2 small), MediaPipe
  segmentation.
- Imaging: libultrahdr (Ultra HDR gainmap JPEG), Android HEIF writer,
  DngCreator, ExifInterface.
- Video: MediaCodec + MediaMuxer; Media3 Transformer for post.
- Desktop calibration: Python, colour-science, rawpy/LibRaw, exiftool,
  OpenCV, NumPy; outputs .cube LUTs + JSON lens/noise profiles.
- Crash/remote: Firebase Crashlytics + Remote Config (quirks table,
  LUT updates).

======================================================================
11. APP FEATURES / UX
======================================================================
- 3:2 viewfinder with live look; zoom ring with click stops; aperture
  dial f/2.8-f/22 (simulated DOF + vignette).
- Picture Styles: Standard (default), Portrait, Landscape, Neutral,
  Faithful, Monochrome; video: Canon 709-style, Standard, Log (10-bit
  only).
- Modes: Auto, Av, M (manual ISO/shutter/focus/Kelvin WB), Video.
- Pro tools (all baseline-capable): live histogram, level, focus peaking,
  zebras, manual focus with magnifier, AE/AWB lock, EV +/-4 in 1/3
  steps; waveform/false color in video.
- Capability screen: shows the phone's tier and why options differ;
  "Export capability report" (JSON) replaces screenshots for adding
  new test devices.
- Before/after; re-edit later (RAW + depth stored); export JPEG (Ultra
  HDR) / HEIF / DNG; MP4 (HEVC), plus APV/log where unlocked.
- Honest labels: "24*" on phones that can't reach 24mm; badge when a
  stop is a digital crop.

======================================================================
12. PERFORMANCE + THERMAL BUDGETS (BASELINE)
======================================================================
- Preview with live look: 30 fps minimum at 1080p preview, GPU <= 8 ms.
- Still processing: background service, max 2 bursts queued; memory
  cap ~1.2 GB for the merge (8 GB device) [ESTIMATE].
- 4K30 video chain: <= 12 ms per frame; 20-minute recording without
  thermal throttling to SEVERE (PowerManager thermal status listener
  steps down: grain off -> bokeh off -> 1080p).
- Startup to first preview frame: < 1.0 s (profile cached).

======================================================================
13. PHASED ROADMAP (solo estimates; each phase gated on the baseline device)
======================================================================
Phase 0 - Calibration + name check (3-4 weeks)
  R5 II RAW+JPEG fit (tone curve + LUT), lensfun import, noise model,
  baseline device ColorChecker / flat-field / noise profiling. Check
  "Redline" on Play Store and USPTO.
  Gate: XYZ->Standard hold-out mean dE2000 < 2.5; baseline chart < 2.0.
Phase 1 - Capability engine + baseline camera (4-6 weeks)
  DeviceProfile, self-test, quirks table, capability report export,
  preview, RAW/JPEG capture, all [BASELINE-VERIFY] items answered.
  Gate: default session set opens and runs on the baseline device 100/100 times.
Phase 2 - Stills look pipeline (6-8 weeks)
  RAW burst merge, super-res at 50/70, color/tone, vignette, FOV,
  sharpening, grain, Ultra HDR output.
  Gate: Section 6 timing targets met on the baseline device.
Phase 3 - Simulated DOF/bokeh (6-10 weeks)
  Depth + segmentation, CoC model, 9-blade renderer, edge refinement.
  Gate: < 5% of a 200-image baseline test set needing manual fix.
Phase 4 - Video (6-8 weeks)
  ISP flat curve + GPU chain, Canon 709-style, 24p, stabilization;
  then flagship unlocks (HLG10, log, APV, 4K120/8K).
  Gate: 20-min 4K30 record on the baseline device without SEVERE throttling.
Phase 5 - Flagship tier + device matrix + blind A/B (4-6 weeks)
Phase 6 - Closed beta (Play Console) and launch (2-4 weeks)
Total ~7-11 months solo. MVP (capability engine + color/tone/FOV/
vignette stills, no bokeh, no video look) ~2-3 months, fully baseline-ready.

======================================================================
14. TEST MATRIX
======================================================================
- Release gate: baseline device (the maintainer's) - automated instrumented tests of
  every default + manual shoot list (skin, sky, foliage, neon,
  tungsten interior, backlit portrait, night street).
- Flagship ceiling (rent time on Firebase Test Lab / Samsung Remote
  Test Lab, or borrow): Galaxy S26 Ultra (200 MP, 3x 67mm + 5x, 10-bit,
  APV), Pixel 11 Pro (50 MP 1/1.3", 5x, Tensor G6), Pixel 10 Pro, one
  Chinese Ultra (Vivo X300 Ultra or Oppo Find X9 series).
- Breadth: Galaxy A-series, Motorola mid-range, one LIMITED/LEGACY
  phone (Basic tier must still work).

======================================================================
15. BUDGET (ROUGH)
======================================================================
- R5 II + RF 24-70: not needed. ColorChecker Classic/Passport ~$100;
  sample packs ~$1-5. LibRaw, lensfun, exiftool, DNG Converter: free.
- Baseline device: owned. Flagship access: remote device labs (Firebase Test
  Lab pay-as-you-go; Samsung Remote Test Lab free) before buying any
  used flagship (~$600-1,000 each).
- Google Play developer account: $25 one-time.
(Approximate; confirm before buying.)

======================================================================
16. RISKS & MITIGATIONS
======================================================================
- baseline default can't hold 4K30 with the full chain -> drop grain/
  sharpen to cheaper variants first; 1080p fallback is last resort.
- RAW burst slower than expected on baseline -> fewer frames at 24/35mm,
  keep super-res only for 50/70mm.
- 70mm crop detail too soft -> 6 MP output, user-visible "digital"
  badge, push super-res frame count, honest marketing.
- OEM HAL fragmentation -> self-test + feature-combination queries +
  remote quirks table.
- Bokeh artifacts -> confidence gating, guided filter, strength slider.
- Trademark/IP -> no Canon files/names in product; own fitted LUTs;
  never spoof EXIF make/model.
- [ESTIMATE] look values -> hold-out testing, blind A/B, remote LUT
  updates.

======================================================================
17. DECISIONS STILL OPEN
======================================================================
1. Free, paid, or subscription.
2. Solo or hire help (native C++/GPU is the critical skill).
3. Personal tool vs Play Store release (adds privacy policy, permission
   review, trademark care).
4. Default zoom stop: plan assumes 35mm (Canon-classic framing, 5.8 MP
   crop on the baseline device). Alternative: 24* for max resolution.
5. Whether flagship-only features may become defaults on those phones
   (plan: only "adaptive defaults" in 4.4; everything else opt-in).

======================================================================
18. KEY SOURCES
======================================================================
  https://www.canon-europe.com/cameras/eos-r5-mark-ii/specifications
- Canon R5 II manual, specifications:
  https://cam.start.canon/en/C017/manual/html/UG-10_Reference_0110.html
- Canon Custom Picture guide (Canon 709 / Picture Style):
  https://cam.start.canon/he/C017/guide2/html/CP-02_Settings_0050.html
- Canon Picture Style overview: https://www.canon.sk/pro/infobank/picture-style
- Canon Europe, RF 24-70 f/2.8L specs:
  https://www.canon-europe.com/lenses/rf-24-70mm-f2-8l-usm-lens/specifications
- OpticalLimits RF 24-70 review (distortion/vignetting):
  https://opticallimits.com/canon/canon-rf-24-70mm-f-2-8-usm-l-is-review-30mp/
- Focus Review (RAW distortion ~1.5% at 70mm):
  https://focus-review.com/en/review-canon-rf-24-70mm-f-2-8l-is-usm/
- The-Digital-Picture RF 24-70 review (CA in bokeh at 70mm, flare):
  https://www.the-digital-picture.com/Reviews/Canon-RF-24-70mm-F2.8-L-IS-USM-Lens.aspx
- Android CameraCharacteristics reference:
  https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- Android multi-camera API:
  https://developer.android.com/media/camera/camera2/multi-camera
- Android HDR video capture:
  https://developer.android.com/media/camera/camera2/hdr-video-capture
- AOSP 10-bit camera output:
  https://source.android.com/docs/core/camera/10-bit-camera-output
- lensfun database (RF 24-70 F2.8L distortion/vignetting):
  https://github.com/lensfun/lensfun/blob/master/data/db/mil-canon.xml
- LibRaw color matrices (R5 II ColorMatrix):
  https://github.com/LibRaw/LibRaw/blob/master/src/tables/colordata.cpp
- Photons to Photos PDR chart: https://www.photonstophotos.net/Charts/PDR.htm
- R5 II read-noise table (Claff data):
  https://alpereninyeri.com/araclar/noise-testi/camera/canon-eos-r5-mark-ii-es
- PetaPixel on R5 II dynamic range:
  https://petapixel.com/2024/08/19/the-canon-eos-r5-iis-dynamic-range-performance-is-complicated/
- DPReview R5 II review: https://www.dpreview.com/reviews/canon-eos-r5-mark-ii-review/
- DPReview R5 II sample gallery: https://www.dpreview.com/sample-galleries/3976759896
- digitalkamera.de R5 II + RF 24-70 test images:
  https://www.digitalkamera.de/Meldung/Labortest_und_Testbilder_der_Canon_EOS_R5_Mark_II_mit_RF_24-70_mm_2_8L/14375.aspx
- Canon Picture Style "Standard":
  https://global.canon/en/imaging/picturestyle/style/standard.html
- camicc (DCP -> CLUT incl. HueSatMap/LookTable):
  https://github.com/rafaelcgs10/camicc
- ePHOTOzine RF 24-70 CA measurements:
  https://www.ephotozine.com/article/canon-rf-24-70mm-f-2-8l-is-usm-lens-review-36048/performance
- M. Brown, ICCV'19 camera pipeline tutorial (3D LUT picture styles):
  https://www.eecs.yorku.ca/~mbrown/ICCV19_Tutorial_MSBrown.pdf
- Android 16 camera features (CCT WB, night mode indicator, HEIC Ultra
  HDR, APV): https://developer.android.com/about/versions/16/features
- Low Light Boost AE:
  https://developer.android.com/media/camera/lowlight/low-light-boost-ae
- CameraX 1.5 Feature Group API:
  https://developer.android.com/blog/posts/beyond-single-features-guaranteeing-feature-combinations-with-camera-x-1-5
- Ultra HDR (AOSP): https://source.android.com/docs/core/camera/ultra-hdr
- Galaxy S26 Ultra specs (GSMArena):
  https://www.gsmarena.com/samsung_galaxy_s26_ultra_5g-14320.php
- iPhone 18 Pro vs Pixel 11 Pro vs S26 Ultra (DPReview):
  https://www.dpreview.com/news/iphone-18-pro-vs-pixel-11-pro-vs-galaxy-s26-which-flagship-wins/
- Wronski et al., Handheld Multi-Frame Super-Resolution (SIGGRAPH 2019).

======================================================================
19. NUMERIC LOOK SPEC - STARTER VALUES
======================================================================
  r_d = r_u * (a*r_u^3 + b*r_u^2 + c*r_u + 1 - a - b - c)
  r normalized so r = 1 at half the SHORT image side (3:2 frame:
  long-edge midpoint r = 1.5, corner r = 1.803). +% = pincushion.
   24mm  a=  0.0259091 b= -0.0662025 c=  0.0194773   edge -1.15%  corner -0.74%
   31mm  a=  0.0097562 b= -0.0218905 c= 0.00599528   edge -0.12%  corner +0.30%
   35mm  a=  0.0108877 b= -0.0277519 c=   0.021455   edge +0.19%  corner +0.77%
   42mm  a=-0.00122003 b=  0.0168115 c= -0.0242082   edge +0.60%  corner +1.25%
   50mm  a= 0.00135005 b=  0.0040131 c=-0.00177737   edge +0.73%  corner +1.42%
   58mm  a=  0.0012503 b= 0.00371153 c= 0.00370853   edge +0.95%  corner +1.74%
   70mm  a=-0.00796799 b=  0.0352717 c= -0.0302904   edge +1.00%  corner +1.63%
  To EMULATE, apply the forward model to an undistorted image
  (inverse-map each output pixel).

19.2 Vignetting [MEASURED - lensfun, pa model, focus at infinity]
  V(r) = 1 + k1*r^2 + k2*r^4 + k3*r^6, r = 1 at the image corner
  (half diagonal). Multiply linear RGB by V(r). Uncorrected values:
   24mm f/2.8  k1= -0.5654 k2= -0.9197 k3=  0.7083   corner -2.16 EV  r0.7 -0.77 EV
   24mm f/4    k1= -0.5853 k2= -0.2026 k3=  0.1265   corner -1.56 EV  r0.7 -0.56 EV
   24mm f/5.6  k1= -0.7571 k2=  0.3014 k3= -0.1503   corner -1.34 EV  r0.7 -0.55 EV
   24mm f/8    k1= -0.6774 k2=  0.0560 k3=  0.0643   corner -1.17 EV  r0.7 -0.54 EV
   24mm f/22   k1= -0.6455 k2= -0.0165 k3=  0.1074   corner -1.17 EV  r0.7 -0.53 EV
   31mm f/2.8  k1= -0.5881 k2= -0.1696 k3=  0.0799   corner -1.63 EV  r0.7 -0.56 EV
   31mm f/4    k1= -0.6159 k2=  0.3321 k3= -0.2508   corner -1.10 EV  r0.7 -0.42 EV
   31mm f/5.6  k1= -0.5222 k2= -0.0042 k3=  0.0825   corner -0.85 EV  r0.7 -0.41 EV
   31mm f/8    k1= -0.5253 k2=  0.0006 k3=  0.0803   corner -0.85 EV  r0.7 -0.41 EV
   31mm f/22   k1= -0.5314 k2=  0.0096 k3=  0.0762   corner -0.85 EV  r0.7 -0.41 EV
   35mm f/2.8  k1= -0.6149 k2=  0.1238 k3= -0.1826   corner -1.62 EV  r0.7 -0.50 EV
   35mm f/4    k1= -0.5860 k2=  0.4987 k3= -0.4163   corner -1.01 EV  r0.7 -0.35 EV
   35mm f/5.6  k1= -0.4460 k2= -0.0179 k3=  0.0656   corner -0.73 EV  r0.7 -0.35 EV
   35mm f/8    k1= -0.4534 k2= -0.0339 k3=  0.0906   corner -0.73 EV  r0.7 -0.36 EV
   35mm f/22   k1= -0.4680 k2= -0.0040 k3=  0.0721   corner -0.74 EV  r0.7 -0.36 EV
   42mm f/2.8  k1= -0.6503 k2=  0.3433 k3= -0.3627   corner -1.60 EV  r0.7 -0.47 EV
   42mm f/4    k1= -0.5735 k2=  0.6377 k3= -0.5496   corner -0.96 EV  r0.7 -0.31 EV
   42mm f/5.6  k1= -0.4517 k2=  0.1466 k3= -0.0756   corner -0.69 EV  r0.7 -0.31 EV
   42mm f/8    k1= -0.4440 k2=  0.0315 k3=  0.0522   corner -0.64 EV  r0.7 -0.33 EV
   42mm f/22   k1= -0.4180 k2=  0.0036 k3=  0.0569   corner -0.64 EV  r0.7 -0.32 EV
   50mm f/2.8  k1= -0.6712 k2=  0.3996 k3= -0.3980   corner -1.60 EV  r0.7 -0.47 EV
   50mm f/4    k1= -0.5081 k2=  0.6394 k3= -0.6064   corner -0.93 EV  r0.7 -0.26 EV
   50mm f/5.6  k1= -0.4348 k2=  0.2726 k3= -0.1962   corner -0.64 EV  r0.7 -0.27 EV
   50mm f/8    k1= -0.3682 k2= -0.0015 k3=  0.0543   corner -0.55 EV  r0.7 -0.28 EV
   50mm f/22   k1= -0.3666 k2=  0.0001 k3=  0.0509   corner -0.55 EV  r0.7 -0.28 EV
   58mm f/2.8  k1= -0.8161 k2=  0.5947 k3= -0.4636   corner -1.67 EV  r0.7 -0.54 EV
   58mm f/4    k1= -0.4843 k2=  0.6471 k3= -0.6506   corner -0.97 EV  r0.7 -0.25 EV
   58mm f/5.6  k1= -0.4404 k2=  0.3944 k3= -0.3063   corner -0.63 EV  r0.7 -0.25 EV
   58mm f/8    k1= -0.3347 k2= -0.0107 k3=  0.0559   corner -0.49 EV  r0.7 -0.25 EV
   58mm f/22   k1= -0.3374 k2= -0.0020 k3=  0.0499   corner -0.49 EV  r0.7 -0.25 EV
   70mm f/2.8  k1= -0.8668 k2=  0.7177 k3= -0.4994   corner -1.51 EV  r0.7 -0.54 EV
   70mm f/4    k1= -0.4076 k2=  0.4778 k3= -0.5193   corner -0.86 EV  r0.7 -0.23 EV
   70mm f/5.6  k1= -0.3575 k2=  0.2724 k3= -0.2137   corner -0.51 EV  r0.7 -0.21 EV
   70mm f/8    k1= -0.2930 k2= -0.0162 k3=  0.0537   corner -0.43 EV  r0.7 -0.22 EV
   70mm f/22   k1= -0.3010 k2= -0.0001 k3=  0.0450   corner -0.43 EV  r0.7 -0.22 EV
  Notes: falloff barely improves past f/5.6-f/8 (natural cos^4 +
  design); the big f/2.8 jump is mechanical vignetting, which also
  produces cat's-eye bokeh near corners (19.5).
  [ESTIMATE] In-camera JPEG residual: multiply EV values by ~0.3.

19.3 Color & tone (Picture Style Standard)
  [PUBLISHED] Standard = default style; sharpness "slightly high",
  saturation "slightly high", contrast 0, color tone 0; vivid colors,
  skin rendering emphasized; Canon keeps Standard consistent across
  EOS models ("color uniformity is ensured").
  [ESTIMATE] starter values until the 9.1 fit is done:
  - Tone: S-curve on top of sRGB; 18% grey lands ~sRGB 115-120;
    highlight clip ~+3.0 to +3.3 EV over mid-grey (HTP adds ~+1 EV);
    shadow toe slightly crushed vs Adobe default (Canon/Adobe
    "Camera Standard" curves are more contrasty than Adobe Standard).
  - Saturation: ~+10-15% chroma overall vs colorimetric; strongest
    in mid-saturation reds/oranges/greens, compressed near gamut edge.
  - Skin: hue held warm (slightly toward orange-red), magenta
    suppressed, smooth gradient; Adobe's Canon look tables push skin
    saturation up to ~1.7x in places.
  - Greens: slightly toward yellow; blues: clean, mild cyan lean;
    highlights desaturate gracefully toward white (no hue skew).
  - White balance: Canon AWB "Ambience priority" default leaves a
    little warmth under tungsten (don't fully neutralize). Model as
    ~25-35% of the tungsten cast retained.
  - Sharpening (newer EOS "Standard"): Strength ~4, Fineness ~2-4,
    Threshold ~4 on a 0-7/1-5 scale. Confirm from MakerNotes.

19.4 Sensor noise / dynamic range [MEASURED - Photons to Photos]
  - Max photographic dynamic range 11.45 EV (original R5: 11.85).
  - Base ISO 100: read noise ~2.38 DN / ~7.6 e-; full well ~50,723 e-;
    unity gain ~ISO 320.
  - Dual gain switches in at ISO 500 (read noise drops ~1.5 stops);
    effectively ISO-invariant from ~ISO 1600.
  - Electronic shutter mode is noisier at base ISO (DPReview).
  - Pixel pitch ~4.39 um (36 mm / 8192 px).
  Grain synthesis: Poisson-Gaussian model. For an "equivalent R5 II
  ISO", scale phone noise to what a 4.39 um, 50.7k e- full-well
  pixel would show at 45 MP, then resample to output size. Canon
  in-camera NR (Standard, "Standard" NR level) leaves fine, mostly
  luminance grain, very little chroma. In-camera Neural NR is
  optional and off by default.

19.5 Depth of field & bokeh [PUBLISHED optics + computed]
  Background blur disc (subject distance s, background at infinity):
  b = f^2 / (N*(s - f)) on a 36 mm-wide sensor. Width as % of frame
  (and px at 8192 wide):
      focal  f/2.8 s=1m      s=2m          s=4m
      24mm   0.59% (48px)    0.29% (24)    0.14% (12)
      35mm   1.26% (103)     0.62% (51)    0.31% (25)
      50mm   2.61% (214)     1.27% (104)   0.63% (51)
      70mm   5.23% (428)     2.52% (206)   1.24% (101)
  At f/4 multiply by ~0.7. Use % of frame width so it scales to any
  output size.
  Phone gap: a 1/1.3" main camera at f/1.7 is ~f/6 equivalent, so
  its natural background blur is ~2.1x smaller than f/2.8 full
  frame; a 1" sensor at f/1.6 is ~f/4.4 (1.6x smaller); a 1/2.55"
  sensor at f/1.8 is ~f/11 (3.9x smaller). The simulation adds the
  difference, not the whole blur.
  Bokeh shape: 9 rounded blades -> near-circular disks; [ESTIMATE]
  cat's-eye at corners wide open: disk truncated to ~60-70% width at
  24mm f/2.8, ~75-85% at 70mm; round by f/4-f/5.6 (tracks the
  mechanical vignetting jump in 19.2). Fairly even disk illumination,
  faint brighter rim, no onion rings (molded aspheres are low-ripple
  but [ESTIMATE] allow a very faint texture). Longitudinal CA:
  slight magenta fringe in front of focus, green behind, visible at
  70mm f/2.8 only.
  Lateral CA [MEASURED by ePHOTOzine/Imatest]: near zero -> add none.

19.6 Field of view [PUBLISHED]
  Diagonal 84 deg (24mm) -> 34 deg (70mm); horizontal 74 -> 29;
  vertical 53 -> 19.5. Output 3:2, 8192 x 5464 (L), 6000 x 4000 (M).
  Phone crop factor for a 35mm-equivalent target:
  crop = target_eq_mm / phone_eq_mm of the lens used.

19.7 Video starter values
  Picture Style Standard (same LUT as stills) for "matches photos";
  Canon 709-style for film-like: lower contrast than Standard, softer
  highlight shoulder, skin-first color [PUBLISHED]; [ESTIMATE] fit it
  from R5 II Canon 709 clips posted publicly (YouTube downloads of
  ungraded footage are lossy - prefer creators' shared original MP4s).


19.8 Baseline crop / output table [ESTIMATE until 3.3 is confirmed]
  Stop   crop from 26mm   native crop px (3:2)   default output
  24*    1.00x            4000x2667 (10.7 MP)    4000x2667
  28     1.08x            3700x2467 ( 9.1 MP)    4000x2667
  35     1.35x            2960x1973 ( 5.8 MP)    4000x2667 (super-res)
  50     1.93x            2070x1380 ( 2.9 MP)    3000x2000 (super-res)
  70     2.70x            1480x987  ( 1.5 MP)    3000x2000 (super-res)

END OF PLAN

```
