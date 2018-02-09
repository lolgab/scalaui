package examples

object BooleanConversionTest {
  def main(args: Array[String]): Unit = {
    val one = 1.asInstanceOf[Boolean]
    val zero = 0.asInstanceOf[Boolean]

    println(s"one = $one")
    println(s"zero = $zero")

    assert(one == true)
    assert(zero == false)
  }
}
