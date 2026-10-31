package scalaui.cms

/** Built-in profiles, generated programmatically (no embedded binary data). */
object Profiles:
  private val D65xy = (0.3127, 0.3290)
  private val SrgbCurve = Curve.Parametric(3, Array(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045))

  lazy val sRGB: Profile = Profile.parse(ProfileWriter.fromPrimaries("sRGB IEC61966-2.1", (0.64, 0.33), (0.30, 0.60), (0.15, 0.06), D65xy, SrgbCurve))
  lazy val displayP3: Profile = Profile.parse(ProfileWriter.fromPrimaries("Display P3", (0.68, 0.32), (0.265, 0.69), (0.15, 0.06), D65xy, SrgbCurve))
  lazy val adobeRGB: Profile = Profile.parse(ProfileWriter.fromPrimaries("Adobe RGB (1998)", (0.64, 0.33), (0.21, 0.71), (0.15, 0.06), D65xy, Curve.Gamma(563.0 / 256)))
  lazy val linearSRGB: Profile = Profile.parse(ProfileWriter.fromPrimaries("sRGB linear", (0.64, 0.33), (0.30, 0.60), (0.15, 0.06), D65xy, Curve.Identity))
  lazy val rec2020Linear: Profile = Profile.parse(ProfileWriter.fromPrimaries("Rec. 2020 linear", (0.708, 0.292), (0.170, 0.797), (0.131, 0.046), D65xy, Curve.Identity))
  lazy val gray22: Profile = Profile.parse(ProfileWriter.gray("Gray gamma 2.2", Curve.Gamma(2.2)))
