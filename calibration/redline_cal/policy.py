"""Which RAW+JPEG pairs are valid training data for the Standard look (docs/PLAN.md section 9)."""
from __future__ import annotations

from dataclasses import dataclass, field

from .metadata import Shot

NORMAL = {"Normal", "0"}


@dataclass(frozen=True)
class StandardPolicy:
    """Accept only JPEGs rendered with an unmodified Standard Picture Style.

    Auto Lighting Optimizer is scene-adaptive. "Standard" is the camera default and is
    accepted but recorded, so the fitter can weight or ablate it; Low/High are rejected.
    Highlight Tone Priority changes the tone curve and is rejected.
    """

    models: frozenset[str] = frozenset({"EOS R5 Mark II"})
    picture_styles: frozenset[str] = frozenset({"Standard"})
    alo_allowed: frozenset[str] = frozenset({"Off", "Standard"})
    htp_allowed: frozenset[str] = frozenset({"Off"})
    require_normal: tuple[str, ...] = ("contrast", "saturation", "color_tone")
    require_dlo_off: bool = True
    extra_reasons: tuple[str, ...] = field(default=())

    def evaluate(self, s: Shot) -> list[str]:
        """Return rejection reasons; an empty list means usable."""
        r: list[str] = []
        if s.model not in self.models:
            r.append(f"model:{s.model}")
        if s.picture_style not in self.picture_styles:
            r.append(f"picture_style:{s.picture_style}")
        for f in self.require_normal:
            v = getattr(s, f)
            if v is not None and v not in NORMAL:
                r.append(f"{f}:{v}")
        if s.auto_lighting_optimizer is not None and s.auto_lighting_optimizer not in self.alo_allowed:
            r.append(f"alo:{s.auto_lighting_optimizer}")
        if s.highlight_tone_priority is not None and s.highlight_tone_priority not in self.htp_allowed:
            r.append(f"htp:{s.highlight_tone_priority}")
        if self.require_dlo_off and s.digital_lens_optimizer not in (None, "Off"):
            r.append(f"dlo:{s.digital_lens_optimizer}")
        if s.picture_style is None:
            r.append("missing_makernotes")
        return r
