# Generating the packed tide grid

Everything here is plain Java 17 with no third party dependency, which is
deliberate: the converter reads HDF5 and writes the packed grid on its own, so
regenerating the data needs nothing but a JDK.

## The short way

[`build.sh`](build.sh) does everything below, in order, and skips any step
whose result is already present. Run it once and it downloads, extracts,
compiles, converts and verifies; run it again and it does nothing but
re-check. The sections that follow explain what each step does and why.

```sh
./build.sh
```

## 1. Get the source data

EOT20 is published on SEANOE:

- Landing page: https://www.seanoe.org/data/00683/79489/
- DOI: https://doi.org/10.17882/79489
- License: CC BY 4.0 (see [`../DATA-LICENSE.md`](../DATA-LICENSE.md))

There is a single download, `85762.zip`, of about **2.33 GB**. A few things
about it are worth knowing before you start.

It contains two archives, `ocean_tides.zip` and `load_tides.zip`. **Only the
first is used here.** The load tides describe the deformation of the sea floor
under the water column; they matter for altimetry, not for knowing when the
sea is high.

The server ignores HTTP suffix ranges, so asking for the last few kilobytes of
the archive hands you the whole file instead. It does honour ordinary ranges,
so an interrupted download can be resumed normally.

```sh
curl -C - --retry 20 --retry-all-errors -o 85762.zip \
  https://www.seanoe.org/data/00683/79489/data/85762.zip

unzip 85762.zip ocean_tides.zip
unzip ocean_tides.zip -d eot20
```

You should end up with seventeen files named `<NAME>_ocean_eot20.nc`, one per
constituent, each about 127 MB.

Despite the `.nc` extension these are **HDF5 files, not classic netCDF**. That
is why `Hdf5Simple.java` exists. It covers only what these files actually use:
superblock version 0, version 2 object headers whose group members are plain
link messages, and contiguous unfiltered datasets of floating point values.
Anything else is rejected rather than guessed at. Repackaged copies of EOT20
circulating elsewhere store their variable index in a fractal heap and will
not be read by it.

Each file holds `lon` (2881 values), `lat` (1441 values), and four full grids
of 64 bit floats: `amplitude` in centimetres, `phase` in degrees, and `real`
and `imaginary`. The converter uses `amplitude` and `phase` and recomputes the
complex constant itself.

## 2. Look at the data before converting it

```sh
javac -d build *.java
java -Xmx4g -cp build generator.GridStats eot20/ocean_tides
```

This prints the amplitude distribution of every constituent. It is worth
running at least once, because a handful of cells hold values that cannot be
real — S1 reaching 45 m where its 99.99th percentile is 15 cm, T2 reaching
27 m where its 99.99th percentile is 9 cm. Those cells are few, some tens out
of 2.75 million, but they are what forces the quantisation step to be chosen
explicitly rather than derived from the maximum of each grid.

At the recommended step of 0.1 cm the 16 bit encoding reaches 32.77 m, which
holds every constituent except three cells of S1 that the packer reports as
clipped. That is expected, and [`../DATA-LICENSE.md`](../DATA-LICENSE.md)
records it as a change made to the original.

## 3. Convert

```sh
java -Xmx6g -cp build generator.PackEot20 eot20/ocean_tides tides_world.bin all world 0.1
gzip -9 tides_world.bin
```

The arguments are the source directory, the output file, the constituents
(`all`, or a comma separated list of lower case names), the area (`world`, or
`latMin,latMax,lonMin,lonMax`), and the quantisation step in centimetres.

A step of `0.1`, one millimetre, is the recommended value. It bounds the error
at 3 mm of tide height and 20 seconds on the time of high or low water,
measured over sixty days, while compressing more than four times better than
letting each grid set its own scale. The run takes a few seconds and produces
a file of about 179 MB, 20 MB once compressed.

Cropping to an area is supported but is meant for testing. The application
ships one world file.

## 4. Verify the result

```sh
java -cp build/classes generator.TideCheck build/tides_world.bin --check
```

The converter is only useful if what comes out predicts real tides, so the
last step reads the packed file back and compares its prediction with
published tide tables. It exits non-zero when the comparison fails, which is
what makes `build.sh` usable unattended.

`TideCheck` is a second, independent implementation of the prediction, written
from the format description in this file rather than shared with the engine
that ships in the application. That is the point: two implementations agreeing
is evidence, while an engine checking a file it wrote itself would only prove
it is self-consistent.

It checks twelve ports over a fixed three day window, spread from 61 degrees
north to 14 degrees south and across the date line, so that a fault in the
longitude wrap, the southern half of the grid or the bitmap index cannot hide:

| port | latitude | longitude | worst time gap |
|------|---------:|----------:|---------------:|
| Anchorage, Alaska | 61.24 | -149.89 | 45 min |
| Adak, Alaska | 51.86 | -176.64 | 12 min |
| Saint-Malo, France | 48.65 | -2.03 | 31 min |
| Atlantic City, New Jersey | 39.36 | -74.42 | 3 min |
| San Francisco, California | 37.81 | -122.47 | 20 min |
| Port San Luis, California | 35.17 | -120.75 | 9 min |
| Midway Atoll | 28.21 | -177.36 | 10 min |
| Honolulu, Hawaii | 21.30 | -157.86 | 44 min |
| Wake Island | 19.29 | 166.62 | 6 min |
| San Juan, Puerto Rico | 18.46 | -66.12 | 33 min |
| Apra Harbor, Guam | 13.44 | 144.66 | 14 min |
| Pago Pago, American Samoa | -14.28 | -170.69 | 26 min |

Heights are not compared directly, because every national table uses its own
datum: the American ones are referenced to mean lower low water, the French
one to the chart datum. What is compared is the **range** between consecutive
tides, which no choice of datum can shift, and for Saint-Malo alone the height
above chart datum, since there the datum is the lowest astronomical tide that
the model can work out for itself.

The tolerances are one hour on time, and on range the larger of 40 cm and 15
per cent. They are wide because a global model on a 1/8 degree grid cannot
reproduce what happens in shallow water near a coast — at Saint-Malo, low
water really runs about half an hour late. What the test is for is catching a
broken conversion, which moves tides by hours or by metres.

Ports where the model is known to fail are deliberately left out, rather than
included with tolerances wide enough to let them pass. Seattle, at the end of
Puget Sound, comes out four and a half hours off; Boston falls on cells the
model marks as land and yields nothing at all. Those are limits of the model,
not of the conversion, and a regression test that accepted them would no
longer be testing anything.

The same tool prints a plain tide table for any position:

```sh
java -cp build/classes generator.TideCheck build/tides_world.bin 48.6486 -2.0261 3
```

## 5. Output format

Little endian throughout. Longitudes run from 0 to 360 degrees.

```
offset  type        meaning
0       char[8]     "EOT20PK2"
8       int32       number of constituents
12      float64     longitude of the first column
20      float64     longitude step in degrees
28      int32       number of columns
32      float64     latitude of the first row
40      float64     latitude step in degrees
48      int32       number of rows
52      int32       number of stored ocean cells
56      int64[]     occupancy bitmap, one bit per cell, row major,
                    ceil(rows * columns / 64) words
        int32[]     index of the first stored cell of each row, one per row
        per constituent:
          char[8]   name, null padded, lower case
          float32   scale of the real part, centimetres per unit
          float32   scale of the imaginary part, centimetres per unit
          int16[]   real part, stored cells only
          int16[]   imaginary part, stored cells only
```

To read the cell at `(row, column)`, test its bit in the bitmap. If it is set,
its position in the stored arrays is the row index plus the number of set bits
earlier in that same row. That keeps random access cheap while letting the
file stay memory mapped, and drops the third of the grid that is land.

The complex constant of a constituent is stored as
`amplitude * exp(-i * phase)`, the convention used by pyTMD and by the
prediction engine, so `real = amplitude * cos(phase)` and
`imaginary = -amplitude * sin(phase)`.

Cells are stored when at least one constituent carries data there. A
constituent with nothing at a stored cell writes zero, which contributes
nothing to the sum.

## 6. Publish

Attach `tides_world.bin.gz` to a GitHub Release of this repository, put
`DATA-LICENSE.md` beside it, quote the CC BY attribution in the release notes,
and point the manifest at the asset. Bump the version on every regeneration.
