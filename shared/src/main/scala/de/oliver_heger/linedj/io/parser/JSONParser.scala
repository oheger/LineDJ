/*
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package de.oliver_heger.linedj.io.parser

import scala.collection.immutable.IndexedSeq
import scala.language.implicitConversions

/**
  * Defines a parser for JSON using the parser combinator library.
  *
  * Note that this is not a general purpose JSON parser, but is specifically
  * tailored for parsing JSON files containing metadata for songs. Such files
  * contain an array of song data in the first level. For each song a bunch of
  * properties can be defined, but there is no nesting of objects, and
  * properties cannot be arrays. All property values are stores as strings.
  *
  * This code is derived from chapter 9 of "Functional Programming in Scala"
  * by Chiusano and Bjarnason.
  */
object JSONParser:
  type JSONData = IndexedSeq[Map[String, String]]

  def jsonParser[Parser[+_]](P: Parsers[Parser]): Parser[JSONData] =
    import P.*

    def array = surround(token("["), token("]"))(
      obj sep token(",") map (vs => vs.toIndexedSeq)) scope "array"
    def obj = surround(token("{"), token("}"))(
      keyval sep token(",") map (kvs => kvs.toMap)) scope "object"
    def keyval = escapedQuoted ** (token(":") *> lit)
    def lit = scope("literal"):
      doubleString |
      escapedQuoted
    root(whitespace *> array)
