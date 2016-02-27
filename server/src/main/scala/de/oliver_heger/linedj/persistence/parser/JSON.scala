/*
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package de.oliver_heger.linedj.persistence.parser

import scala.language.{higherKinds, implicitConversions}

trait JSON

/**
  * Defines a parser for JSON using the parser combinator library.
  *
  * This code is derived from chapter 9 of "Functional Programming in Scala"
  * by Chiusano and Bjarnason.
  */
object JSON {
  case object JNull extends JSON
  case class JNumber(get: Double) extends JSON
  case class JString(get: String) extends JSON
  case class JBool(get: Boolean) extends JSON
  case class JArray(get: IndexedSeq[JSON]) extends JSON
  case class JObject(get: Map[String, JSON]) extends JSON

  def jsonParser[Parser[+_]](P: Parsers[Parser]): Parser[JSON] = {
    // we'll hide the string implicit conversion and promote strings to tokens instead
    import P._
    // we'll shadow the string implicit conversion from the Parsers trait and promote strings to tokens instead
    // this is a bit nicer than having to write token everywhere
    implicit def string(s: String): Parser[String] = token(P.string(s))

    def array = surround("[","]")(
      value sep "," map (vs => JArray(vs.toIndexedSeq))) scope "array"
    def obj = surround("{","}")(
      keyval sep "," map (kvs => JObject(kvs.toMap))) scope "object"
    def keyval = escapedQuoted ** (":" *> value)
    def lit = scope("literal") {
      "null".as(JNull) |
      double.map(JNumber) |
      escapedQuoted.map(JString) |
      "true".as(JBool(true)) |
      "false".as(JBool(false))
    }
    def value: Parser[JSON] = lit | obj | array
    root(whitespace *> (obj | array))
  }
}

/**
 * JSON parsing example.
 */
object JSONExample extends App {
  val jsonTxt = """
{
  "Company name" : "Microsoft Corporation",
  "Ticker"  : "MSFT",
  "Active"  : true,
  "Price"   : 30.66,
  "Shares outstanding" : 8.38e9,
  "Related companies" : [ "HPQ", "IBM", "YHOO", "DELL", "GOOG" ]
}
"""

  val malformedJson1 = """
{
  "Company name" ; "Microsoft Corporation"
}
"""

  val malformedJson2 = """
[
  [ "HPQ", "IBM",
  "YHOO", "DELL" ++
  "GOOG"
  ]
]
"""

  val P = ParserImpl
  import ParserTypes.Parser

  val json: Parser[JSON] = JSON.jsonParser(P)

  def partialTest(split: Int): ParserTypes.Result[JSON] = {
    println(s"partialTest($split)")
    val input1 = jsonTxt.substring(0, split)
    val input2 = jsonTxt.substring(split)
    println(s"Parsing inputs:\n$input1\nand\n$input2")
    P.runPartial(json)(input1) match {
      case f@Failure(err, com, data) =>
        println("Continue after " + f)
        P.runContinue(json)(f, input2, lastChunk = true)
      case s => s
    }
  }

//  println { P.runPartial(json)(jsonTxt) }
//  println("--")
//  println { P.runPartial(json)(malformedJson1) }
//  println("--")
//  println { P.runPartial(json)(malformedJson2) }
//  println("--")
  val failures = (1 until /*jsonTxt.length*/150).foldLeft(Map.empty[Int, ParserTypes.Result[JSON]]) { (m, i) =>
    partialTest(i) match {
      case f: Failure =>
        println("Failure at " + i + ": " + f)
        m + (i -> f)
      case _ => m
    }
  }
  println()
  println("===========  Failures ===========")
  println(failures)
}
