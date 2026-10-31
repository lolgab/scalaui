---
title: brotli
section: Modules
order: 5
description: Brotli decompression, used to read WOFF2 fonts.
---
# brotli

`scalaui.brotli` decompresses Brotli streams (RFC 7932) in pure Scala. It exists to support [WOFF2](font.md#woff-and-woff2) fonts, whose tables are Brotli-compressed. No dependencies.

```scala
import scalaui.brotli.Brotli
```

## API

```scala
object Brotli:
  def decompress(in: Array[Byte], offset: Int = 0, length: Int = -1, sizeHint: Int = 0): Array[Byte]
```

`offset` and `length` select a slice of `in` (the default `length = -1` means to the end); `sizeHint` presizes the output buffer when the decompressed size is known.

```scala
val raw = Brotli.decompress(compressedBytes, sizeHint = expectedSize)
```

The decoder works on whole buffers (no streaming) and includes the RFC's 122,784-byte static dictionary as an embedded resource, so native builds need `nativeConfig.withEmbedResources(true)`. Only decompression exists; there is no encoder.
