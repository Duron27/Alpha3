# Alpha3 starter set of ReShade effects

Bundled with the app (assets `libopenmw-upstream/reshade`), refreshed by the launcher into
`Alpha3/reshade/Shaders/Alpha3 starter/` and `Alpha3/reshade/Textures/Alpha3 starter/` at each start, so edits there
are lost: copy a file elsewhere in `Shaders/` to change it. Chosen for being cheap on phone GPUs and for their
licences (see `handoff/RESHADE_SHADER_LICENCES.md`). Every file keeps its own header.

| Files | From (commit) | Licence |
| --- | --- | --- |
| Vibrance.fx, CAS.fx, Curves.fx, Tonemap.fx, Vignette.fx | [CeeJayDK/SweetFX](https://github.com/CeeJayDK/SweetFX) (93ddf39) | MIT (`licenses/SweetFX-MIT.txt`); CAS.fx is AMD's, MIT, in its header |
| PD80_04_Color_Balance.fx, PD80_04_Color_Temperature.fx, PD80_00_Color_Spaces.fxh | [prod80/prod80-ReShade-Repository](https://github.com/prod80/prod80-ReShade-Repository) (1c2ed5b) | MIT (`licenses/prod80-MIT.txt`) |
| pColors.fx, Oklab.fxh, pUtils.fxh | [GimleLarpes/potatoFX](https://github.com/GimleLarpes/potatoFX) (f55a022) | MIT (`licenses/potatoFX-MIT.txt`) |
| NeoBloom.fx, ColorLab.fxh, FXShaders/*.fxh, Textures/NeoBloom_LensDirt.png | [luluco250/FXShaders](https://github.com/luluco250/FXShaders) (76365e3) | MIT (`licenses/FXShaders-MIT.txt`) |
| cAutoExposure.fx, shared/*.fxh | [papadanku/CShade](https://github.com/papadanku/CShade) (2b22038) | BSD 3-clause (`licenses/CShade-BSD-3-Clause.txt`); shared/cLens.fxh is AMD's MIT, shared/cColor.fxh Colour Developers' BSD 3-clause, in their headers |
| ReShade.fxh | [crosire/reshade-shaders](https://github.com/crosire/reshade-shaders) (slim) | CC0 1.0 (in its header) |
| ReShadeUI.fxh, Textures/lut.png (a neutral 32x32x32 LUT) | written for Alpha3 | GPL-3, as Alpha3 |
