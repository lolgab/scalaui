package scalaui.unicode

import java.nio.ByteBuffer
import java.util.regex.{Matcher, Pattern}

/** Zawgyi-One (legacy, non-standard Myanmar font encoding) detection and Zawgyi -> Unicode conversion.
  *
  * Both are ports of Google's myanmar-tools (https://github.com/googlei18n/myanmar-tools, Apache License 2.0,
  * Copyright 2017 Google LLC): the probabilistic Markov-model detector (`ZawgyiDetector`) and the rule-based
  * transliterator (`TransliterateZ2U`). The model and rules live in the generated `ZawgyiData`.
  */
object Zawgyi:
  /** Probability in `[0, 1]` that `text` is Zawgyi rather than standard Unicode Myanmar.
    * Returns `Double.NegativeInfinity` if the text has no Myanmar characters (treat as Unicode).
    */
  def probability(text: String): Double = Model.predict(text)

  /** True if `text` is more likely Zawgyi than Unicode (probability above `threshold`, default 0.95). */
  def isZawgyi(text: String, threshold: Double = 0.95): Boolean = probability(text) > threshold

  /** Convert Zawgyi-encoded text to standard Unicode Myanmar. */
  def toUnicode(text: String): String =
    var s = text
    var i = 0
    while i < Converter.phases.length do { s = Converter.runPhase(Converter.phases(i), s); i += 1 }
    s

  /** Convert `text` to Unicode if it is detected as Zawgyi (see [[isZawgyi]]), otherwise return it unchanged. */
  def autoConvert(text: String, threshold: Double = 0.95): String =
    if isZawgyi(text, threshold) then toUnicode(text) else text

  // ---- converter -----------------------------------------------------------------------------------------------

  private final class CompiledRule(r: ZawgyiData.Rule):
    val pattern: Pattern = Pattern.compile("^" + r.pattern)
    val substitution: String = r.substitution
    val matchOnStart: Boolean = r.matchOnStart
    val revisit: Int = r.revisit

  private object Converter:
    val phases: Array[Array[CompiledRule]] = ZawgyiData.z2u.map(_.map(new CompiledRule(_)))

    // Faithful port of myanmar-tools Phase.runPhase: at each position every rule is tried in order (each
    // sees the output of the previous), then the scan advances past the rewritten prefix.
    def runPhase(rules: Array[CompiledRule], in: String): String =
      val out = new java.lang.StringBuilder
      var mid = in
      var start = true
      val matchers = rules.map(_.pattern.matcher(""))
      while mid.nonEmpty do
        var found = false
        var ri = 0
        while ri < rules.length do
          val rule = rules(ri)
          if !rule.matchOnStart || start then
            val m: Matcher = matchers(ri)
            m.reset(mid)
            if m.find() then
              found = true
              val rightPart = mid.length - m.group(0).length
              mid = m.replaceFirst(rule.substitution)
              if rule.revisit < 0 then
                val newStart = mid.length - rightPart
                out.append(mid, 0, newStart)
                mid = mid.substring(newStart)
          ri += 1
        if !found then
          out.append(mid.charAt(0))
          mid = mid.substring(1)
        start = false
      out.toString

  // ---- detector ------------------------------------------------------------------------------------------------

  private object Model:
    private val StdCp0 = 0x1000; private val StdCp1 = 0x103F
    private val AftCp0 = 0x104A; private val AftCp1 = 0x109F
    private val ExaCp0 = 0xAA60; private val ExaCp1 = 0xAA7F
    private val ExbCp0 = 0xA9E0; private val ExbCp1 = 0xA9FF
    private val SpcCp0 = 0x2000; private val SpcCp1 = 0x200B
    private val StdOff = 1
    private val AftOff = StdOff + StdCp1 - StdCp0 + 1
    private val ExaOff = AftOff + AftCp1 - AftCp0 + 1
    private val ExbOff = ExaOff + ExaCp1 - ExaCp0 + 1
    private val SpcOff = ExbOff + ExbCp1 - ExbCp0 + 1
    private val EndOff = SpcOff + SpcCp1 - SpcCp0 + 1

    private def indexFor(cp: Int, ssv: Int): Int =
      if cp >= StdCp0 && cp <= StdCp1 then cp - StdCp0 + StdOff
      else if cp >= AftCp0 && cp <= AftCp1 then cp - AftCp0 + AftOff
      else if cp >= ExaCp0 && cp <= ExaCp1 then cp - ExaCp0 + ExaOff
      else if cp >= ExbCp0 && cp <= ExbCp1 then cp - ExbCp0 + ExbOff
      else if ssv == 0 && cp >= SpcCp0 && cp <= SpcCp1 then cp - SpcCp0 + SpcOff
      else 0

    // log-probability differences, size x size
    private val (ssv: Int, delta: Array[Array[Float]]) =
      val b = ByteBuffer.wrap(java.util.Base64.getDecoder.decode(ZawgyiData.modelBase64))
      require(b.getLong() == 0x555A4D4F44454C20L, "bad model magic")
      val version = b.getInt()
      val ssv = if version == 1 then 0 else b.getInt()
      require(b.getLong() == 0x424D41524B4F5620L, "bad markov magic")
      require(b.getInt() == 0, "bad markov version")
      val size = b.getShort().toInt
      val d = Array.ofDim[Float](size, size)
      var i1 = 0
      while i1 < size do
        var entries = b.getShort().toInt
        val fallback = if entries == 0 then 0f else b.getFloat()
        var next = -1
        var i2 = 0
        while i2 < size do
          if entries > 0 && next < i2 then { next = b.getShort().toInt; entries -= 1 }
          d(i1)(i2) = if next == i2 then b.getFloat() else fallback
          i2 += 1
        i1 += 1
      (ssv, d)

    def predict(input: String): Double =
      var prevState = 0
      var total = 0.0
      var seen = false
      var offset = 0
      while offset <= input.length do
        val (cp, cur) =
          if offset == input.length then (0, 0)
          else { val c = input.codePointAt(offset); (c, indexFor(c, ssv)) }
        if prevState != 0 || cur != 0 then
          total += delta(prevState)(cur)
          seen = true
        offset += Character.charCount(cp)
        prevState = cur
      if !seen then Double.NegativeInfinity else 1.0 / (1.0 + math.exp(total))
