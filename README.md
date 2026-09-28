# mybuoy-tides

Offline **tide prediction data** for the MyBuoy application.

MyBuoy needs to give tide times and water heights anywhere in the world, with
no network and no subscription to a tide service. This repository holds the
tooling that turns the published EOT20 global ocean tide model into a single
compact file the application can memory map, plus the licensing and provenance
that come with redistributing that data.

The tidal prediction engine itself is **not** here: it lives with the
application. This repository is only about the data.

## Why EOT20

A tide model that can be shipped inside a commercial product has to be openly
licensed for commercial use, and very few global models are. EOT20 is released
under CC BY 4.0, which permits exactly that provided the authors are credited.
It gives seventeen tidal constituents on a global 1/8 degree grid, which is
enough to predict high and low water to within a few minutes on an open coast.
See [NOTICE.md](NOTICE.md) for the people behind it.

## Contents

- **[`generator/`](generator/)** — the converter and the step by step
  procedure, in Java, with no third party dependency. See
  [generator/README.md](generator/README.md).
- **Releases** — the packed grid (`tides_world.bin`, compressed as `.gz`),
  downloaded and decompressed by the application on first use.
- **[`DATA-LICENSE.md`](DATA-LICENSE.md)** — the CC BY 4.0 terms and the exact
  list of changes made to the original model.

## How the application gets the file

The tide grid travels the same road as the offline map database, and through
the same manifest: there is no separate endpoint. The backend of the
`mybuoy-maps` repository already serves a list of downloadable files, and the
tide grid is simply one more entry in it, declared in
`backend/include/gb_maps.php`. What tells the application that this entry is a
tide model rather than map data is its `Name`, `tides_world`.

```
<backend>/map-checkupdate.php       (JSON manifest, one entry per file)
        |  the app reads this manifest and picks the entry named tides_world
        v
GitHub Release of this repository: tides_world.bin.gz
        |  the app downloads and decompresses
        v
tides_world_<version>.bin on the device, memory mapped by the tide engine
```

The manifest entry to add alongside the map ones:

```php
array(
    'Title'    => 'Tides (world)',
    'Name'     => 'tides_world',
    'Version'  => 1,
    'FileSize'       => 187798600,
    'CompressedSize' => 21120501,
    'Url'            => 'https://github.com/BlackDady/mybuoy-tides/releases/download/tides-v1/tides_world.bin.gz',
),
```

The grid is not bundled in the APK. At around 20 MB compressed it would more
than double the installed package for a feature not everyone uses, so it is
fetched on demand like the maps.

## Size and accuracy

Measured on the full world grid, seventeen constituents, native 1/8 degree
resolution:

| variant | on disk | compressed |
|---------|---------|------------|
| full grid, adaptive scale | 269 MB | 98 MB |
| full grid, 1 mm step | 269 MB | 22 MB |
| **ocean cells only, 1 mm step** | **179 MB** | **20 MB** |

The two reductions are independent and neither is visible in the result.
Quantising to a fixed millimetre step costs at most 3 mm of tide height and
20 seconds on the time of high or low water, measured over sixty days against
the unquantised grid. Dropping land cells costs nothing at all: they held no
data in the first place.

Reducing the *number* of constituents was measured and rejected. Keeping only
the nine largest saves a further 5 MB compressed but shifts the predicted time
of high and low water by up to twenty minutes, which is not a trade worth
making.

## Releases

| Version | Tag | Asset | `.gz` size | Decompressed |
|---------|-----|-------|------------|--------------|
| _none yet_ | | | | |

## License

This repository mixes two things under two licenses:

- **Tide data** (the packed grids published in Releases): derived from EOT20,
  licensed under **CC BY 4.0**, © Hart-Davis et al. / DGFI-TUM, published by
  SEANOE. See [DATA-LICENSE.md](DATA-LICENSE.md).
- **Code** (`generator/`): licensed under the **[MIT](LICENSE)** license.

When publishing a Release, include the CC BY attribution in the release notes
and ship `DATA-LICENSE.md` next to the `.gz` asset.
