# Acknowledgements

This project stands on work that other people made freely available.

## EOT20 — the model itself

**Hart-Davis, Piccioni, Dettmering, Schwatke, Passaro and Seitz**, at the
Deutsches Geodätisches Forschungsinstitut of the Technische Universität
München (DGFI-TUM), built EOT20 from the residual tidal analysis of eleven
satellite altimetry missions, and published it openly under CC BY 4.0 through
SEANOE. Releasing a global tide model under a licence that permits commercial
use is uncommon and is what makes an offline tide feature possible here at
all. Full citation in [DATA-LICENSE.md](DATA-LICENSE.md).

## pyTMD

**Tyler C. Sutterley** and contributors — https://github.com/tsutterley/pyTMD,
MIT License. pyTMD is a clear, readable reference implementation of tidal
prediction: Doodson coefficients, astronomical mean longitudes, Schureman
auxiliary angles and the nodal corrections, together with the convention that
the complex constant of a constituent is `amplitude * exp(-i * phase)`. The
MyBuoy prediction engine follows those formulations; this repository follows
the same phase convention so that the two agree.

## TMD — Tide Model Driver

**Chad A. Greene**, with **Svetlana Erofeeva** and **Laurie Padman** for the
earlier versions — https://github.com/chadagreene/Tide-Model-Driver,
MIT License. TMD is the other end of the same lineage and was a useful
cross-check on how tide models are consumed in practice.

## Tide tables used for verification

The regression test compares the converted grid with published predictions.
Those for the American ports come from the **National Oceanic and Atmospheric
Administration**, whose tide predictions are works of the United States
government and in the public domain; those for Saint-Malo are the French
hydrographic service predictions as republished by maree.info. Only the
handful of values needed to verify the conversion is reproduced, in
`generator/TideCheck.java`.

## Scope of this repository

The code here is a **data converter** and nothing else: it reads the published
EOT20 files and rewrites them in a compact form. The tidal prediction engine
lives with the MyBuoy application, not here.
