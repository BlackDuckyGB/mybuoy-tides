# Tide data license (CC BY 4.0)

The packed tide grids produced and distributed by this project
(`tides_world.bin`, served via GitHub Releases) are **derived from the EOT20
global ocean tide model**:

- **Data:** EOT20 — A global Empirical Ocean Tide model from multi-mission
  satellite altimetry, by Hart-Davis, M., Piccioni, G., Dettmering, D.,
  Schwatke, C., Passaro, M., and Seitz, F. (2021),
  Deutsches Geodätisches Forschungsinstitut, Technische Universität München
  (DGFI-TUM), published by SEANOE.
- **DOI:** https://doi.org/10.17882/79489
- **License:** Creative Commons Attribution 4.0 International (CC BY 4.0) —
  https://creativecommons.org/licenses/by/4.0/

## What this means

The packed grid is an **adapted version** of EOT20 under CC BY 4.0. You are
free to use, share and adapt it, including commercially, provided that you:

1. **give appropriate credit** to the authors named above,
2. **link to the license**, and
3. **indicate whether changes were made** — they were, see below.

## Changes made to the original

The conversion is documented in [`generator/README.md`](generator/README.md).
In short:

- only the **ocean tide** grids are used; the load tide grids are not;
- the `real` and `imaginary` variables are recomputed from `amplitude` and
  `phase` rather than read from the file;
- values are **quantised to a fixed step in centimetres** and stored as 16 bit
  integers instead of 64 bit floats;
- **land cells are dropped**; only cells carrying data are stored, addressed
  through an occupancy bitmap;
- three cells of the S1 constituent are **clipped**: their amplitude in the
  source reaches 45.8 m, beyond what the 16 bit encoding holds at a millimetre
  step, and far beyond anything physical for that constituent, whose 99.99th
  percentile over the whole ocean is 15 cm. No other cell of any constituent is
  clipped;
- no resampling, no smoothing, and no change of resolution: the grid keeps its
  native 1/8 degree spacing and, the three cells above aside, every stored
  value comes from the corresponding source cell.

No other data source is introduced.

## Citation

When redistributing the packed grid, include this notice and cite:

> Hart-Davis, M., Piccioni, G., Dettmering, D., Schwatke, C., Passaro, M., and
> Seitz, F. (2021). EOT20 - A global Empirical Ocean Tide model from
> multi-mission satellite altimetry. SEANOE. https://doi.org/10.17882/79489

The accompanying paper is:

> Hart-Davis, M. G., Piccioni, G., Dettmering, D., Schwatke, C., Passaro, M.,
> and Seitz, F. (2021). EOT20: a global ocean tide model from multi-mission
> satellite altimetry. *Earth System Science Data*, 13(8), 3869–3884.
> https://doi.org/10.5194/essd-13-3869-2021

> This CC BY notice covers the **data** only. The code in this repository is
> covered separately by [LICENSE](LICENSE).
