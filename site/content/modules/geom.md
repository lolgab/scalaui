---
title: geom
section: Modules
order: 1
description: Points, vectors, sizes, rectangles and affine transforms.
---
# geom

`scalaui.geom` is the 2D geometry vocabulary every other module speaks (the equivalent of kurbo). All types are immutable case classes of `Double`s. It has no dependencies.

```scala
import scalaui.geom.*
```

## Types

| Type | Fields | Highlights |
|---|---|---|
| `Point(x, y)` | position | `+ Vec2`, `- Vec2`, `- Point` (gives a `Vec2`), `distance`, `lerp`, `toVec2`, `Point.Zero` |
| `Vec2(x, y)` | displacement | `+`, `-`, `* Double`, unary `-`, `dot`, `cross`, `length`, `lengthSquared`, `normalized`, `toPoint`, `Vec2.Zero` |
| `Size(width, height)` | extent | `area`, `isEmpty` |
| `Rect(x0, y0, x1, y1)` | edges, not origin + size | `width`, `height`, `size`, `center`, `origin`, `contains(Point)`, `union`, `intersect`, `inflate(dx, dy)`, `translate(Vec2)`, `normalized`, `isEmpty` |
| `Affine(a, b, c, d, e, f)` | 2x3 matrix | `*` (composition), `apply(Point)`, `inverse`, `determinant`, `isInvertible`, `transformRectBbox(Rect)`, `isNearIdentity` |

Constructors: `Rect.fromOriginSize(Point, Size)`, `Rect.fromPoints(a, b)` (normalized), `Affine.Identity`, `Affine.translate(Vec2)`, `Affine.scale(s)`, `Affine.scale(sx, sy)`, `Affine.rotate(radians)`.

## Example

```scala
import scalaui.geom.*

val r = Rect(10, 10, 110, 60)
assert(r.contains(Point(50, 30)))
val grown = r.inflate(5, 5)                      // Rect(5, 5, 115, 65)

val m = Affine.translate(Vec2(100, 0)) * Affine.rotate(math.Pi / 2)
val p = m(Point(1, 0))                           // rotated, then moved
val back = m.inverse(p)

val d = Point(3, 4) - Point(0, 0)                // Vec2(3, 4)
println(d.length)                                // 5.0
```

`Rect.contains` is half-open: the left and top edges are inside, the right and bottom edges are outside. `Affine.apply` computes `(a*x + c*y + e, b*x + d*y + f)`.
