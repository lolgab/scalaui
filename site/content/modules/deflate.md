---
title: deflate
section: Modules
order: 4
description: Pure-Scala zlib (inflate and deflate), CRC-32 and Adler-32.
---
# deflate

`scalaui.deflate` is a pure-Scala implementation of zlib. It exists because `java.util.zip` on Scala Native is backed by the C zlib, which breaks cross-compilation with a sysroot. PNG, PDF, WOFF and TIFF all use it. No dependencies.

```scala
import scalaui.deflate.*
```

## API

| Member | Description |
|---|---|
| `Zlib.compress(data, level = 6): Array[Byte]` | zlib stream (header, deflate blocks with LZ77 hash chains and dynamic Huffman codes, Adler-32 trailer). `level` 1 to 9 controls the match search depth |
| `Zlib.decompress(data): Array[Byte]` | validates the header and Adler-32 |
| `Zlib.inflateRaw(data, offset = 0): Array[Byte]` | raw deflate stream (no zlib wrapper), as used inside zip entries |
| `Crc32(data, off = 0, len = -1): Int` | CRC-32 |
| `Adler32(data, off = 0, len = -1): Int` | Adler-32 |
| `DataFormatException` | thrown on corrupt input |

## Example

```scala
import scalaui.deflate.*

val text = ("hello " * 1000).getBytes("UTF-8")
val packed = Zlib.compress(text, level = 9)
val back = Zlib.decompress(packed)
assert(java.util.Arrays.equals(text, back))

val crc = Crc32(text)
```

::: note
The compressor targets compatibility and reasonable ratios, not speed records. Output is a valid zlib stream any decoder accepts.
:::
