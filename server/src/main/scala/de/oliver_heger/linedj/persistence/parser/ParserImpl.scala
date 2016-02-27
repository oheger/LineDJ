/*
 * Copyright 2015-2016 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.persistence.parser

import de.oliver_heger.linedj.persistence.parser.ParserTypes._

import scala.util.matching.Regex

/**
  * Defines the data types used by the parser implementation.
  */
object ParserTypes {

  /** A parser is a kind of state action that can fail. */
  type Parser[+A] = ParseState => Result[A]

  /** `ParseState` wraps a `Location` and provides some extra
    * convenience functions. The sliceable parsers defined
    * in `Sliceable.scala` add an `isSliced` `Boolean` flag
    * to `ParseState`.
    */
  case class ParseState(loc: Location, partialData: List[Any] = Nil, lastChunk: Boolean = true) {
    def advanceBy(numChars: Int): ParseState =
      copy(loc = loc.copy(offset = loc.offset + numChars))
    def input: String = loc.input.substring(loc.offset)
    def slice(n: Int) = loc.input.substring(loc.offset, loc.offset + n)

    def readPartialData(): (Option[Any], ParseState) = partialData match {
      case h :: t =>
        println("readPartialData(): " + h)
        (Some(h), ParseState(loc, t))
      case _ => (None, this)
    }

    def clearPartialData(): ParseState =
    if(partialData.isEmpty) this
    else copy(partialData = Nil)
  }

  /* Likewise, we define a few helper functions on `Result`. */
  sealed trait Result[+A] {
    def extract: Either[ParseError,A] = this match {
      case Failure(e,_, _) => Left(e)
      case Success(a,_) => Right(a)
    }
    /* Used by `attempt`. */
    def uncommit: Result[A] = this match {
      case Failure(e,true, d) => Failure(e,isCommitted = false, d)
      case _ => this
    }
    /* Used by `flatMap` */
    def addCommit(isCommitted: Boolean): Result[A] = this match {
      case Failure(e,c, d) => Failure(e, c || isCommitted, d)
      case _ => this
    }
    /* Used by `scope`, `label`. */
    def mapError(f: ParseError => ParseError): Result[A] = this match {
      case Failure(e,c,d) => Failure(f(e),c, d)
      case _ => this
    }
    def advanceSuccess(n: Int): Result[A] = this match {
      case Success(a,m) => Success(a,n+m)
      case _ => this
    }
    def appendPartialData(data: => Any): Result[A] = this match {
      case Failure(e,c,d) => Failure(e,c, data :: d)
      case _ => this
    }
    def clearPartialData(): Result[A] = this match {
      case Failure(e,c,_) => Failure(e,c, Nil)
      case _ => this
    }
  }
  case class Success[+A](get: A, length: Int) extends Result[A]
  case class Failure(get: ParseError, isCommitted: Boolean, partialData: List[Any]) extends Result[Nothing]

  /** Returns -1 if s1.startsWith(s2), otherwise returns the
    * first index where the two strings differed. If s2 is
    * longer than s1, returns s1.length. */
  def firstNonmatchingIndex(s1: String, s2: String, offset: Int): Int = {
    var i = 0
    while (i < s1.length && i < s2.length && i + offset < s1.length) {
      if (s1.charAt(i+offset) != s2.charAt(i)) return i
      i += 1
    }
    if (s1.length-offset >= s2.length) -1
    else s1.length-offset
  }
}

/**
  * A concrete parser implementation which supports chunk-wise parsing.
  *
  * This implementation is derived from the ''Reference'' class from
  * chapter 9 of "Functional Programming in Scala" by Chiusano and Bjarnason.
  * The original source code was extended to support the parsing of data in
  * multiple chunks.
  */
object ParserImpl extends Parsers[Parser] {

  case class ManyPartialData[+A](results: List[A])

  case class FlatMapPartialData[+A](result: Option[Result[A]])

  case class OrPartialData(parserIndex: Int) {
    def firstParser[A](p: Parser[A], alt: => Parser[A]): Parser[A] =
      if (parserIndex == 1) p else alt

    def alternativeParser[A](p: Parser[A], alt: => Parser[A]): Parser[A] =
      if (parserIndex == 1) alt else p
  }

  val NoManyPartialData = ManyPartialData(Nil)

  private val FirstParser = OrPartialData(1)
  private val AlternativeParser = OrPartialData(2)

  def run[A](p: Parser[A])(s: String): Either[ParseError,A] =
    runPartial(p)(s).extract

  def runPartial[A](p: Parser[A])(s: String): Result[A] = {
    val s0 = ParseState(loc = Location(s), lastChunk = false)
    p(s0)
  }

  def runContinue[A](p: Parser[A])(error: Failure, nextChunk: String, lastChunk: Boolean): Result[A] = {
    val nextInput = error.get.latestLoc.map(l => l.input.substring(l.offset)).getOrElse("") + nextChunk
    println("Input for continue: " + nextInput)
    p(ParseState(Location(nextInput), error.partialData, lastChunk))
  }

  // consume no characters and succeed with the given value
  def succeed[A](a: A): Parser[A] = s => Success(a, 0)

  def or[A](p: Parser[A], p2: => Parser[A]): Parser[A] =
    s => {
      println("or at " + s.loc.offset)
      lazy val alternative = p2
      val (optData, state) = s.readPartialData()
      val orData = optData.map(_.asInstanceOf[OrPartialData]) getOrElse FirstParser
      orData.firstParser(p, alternative)(state) match {
        case Failure(e, false, _) => orData.alternativeParser(p, alternative)(state.clearPartialData()) match {
          case f@Failure(er, false, _) => f.clearPartialData()
          case r => r.appendPartialData(AlternativeParser)
        }
        case r => r.appendPartialData(FirstParser) // committed failure or success skips running `p2`
      }
    }

  def flatMap[A,B](f: Parser[A])(g: A => Parser[B]): Parser[B] =
    s => {
      println("flatMap at " + s.loc.offset)
      val (optData, state) = s.readPartialData()
      val optResult = optData.flatMap(_.asInstanceOf[FlatMapPartialData[A]].result)
      val result = optResult getOrElse f(state)
      result match {
        case Success(a, n) =>
          println("flatMap success: " + a)
          val nextState = if(optResult.isEmpty) state.advanceBy(n).clearPartialData()
          else state
          g(a)(nextState)
          .addCommit(n != 0)
          .advanceSuccess(if(optResult.isEmpty) n else 0).appendPartialData(FlatMapPartialData(Some(result)))
        case f@Failure(_, _, _) =>
          //println("flatMap failure: " + f.get)
          f.appendPartialData(FlatMapPartialData(None))
      }
    }

  def string(w: String): Parser[String] = {
    val msg = "'" + w + "'"
    s => {
      println(s"string($msg) at ${s.loc.offset}")
      val i = firstNonmatchingIndex(s.loc.input, w, s.loc.offset)
      if (i == -1) {
        // they matched
        println("  Found " + w)
        Success(w, w.length)
      }
      else {
        println("  Not found, string is " + (if(s.loc.input.length <= s.loc.offset) "<EMPTY>" else s.input.take(w.length)))
        Failure(s.loc.toError(msg), i != 0, Nil)
      }
    }
  }

  /* note, regex matching is 'all-or-nothing':
   * failures are uncommitted */
  def regex(r: Regex): Parser[String] = {
    val msg = "regex " + r
    s => {
      println(s"$msg at ${s.loc.offset}")
      r.findPrefixOf(s.input) match {
        case Some(m) =>
          if(s.lastChunk || m.length < s.input.length)
            Success(m,m.length)
          else Failure(s.loc.toError(msg), isCommitted = true, Nil)
        case _ => Failure(s.loc.toError(msg), isCommitted = false, Nil)
      }
    }
  }

  def scope[A](msg: String)(p: Parser[A]): Parser[A] =
    s => p(s).mapError(_.push(s.loc,msg))

  def label[A](msg: String)(p: Parser[A]): Parser[A] =
    s => p(s).mapError(_.label(msg))

  def fail[A](msg: String): Parser[A] =
    s => Failure(s.loc.toError(msg), isCommitted = true, Nil)

  def attempt[A](p: Parser[A]): Parser[A] =
    s => p(s).uncommit

  def slice[A](p: Parser[A]): Parser[String] =
    s => p(s) match {
      case Success(_,n) => Success(s.slice(n),n)
      case f@Failure(_,_, _) => f
    }

  /* We provide an overridden version of `many` that accumulates
   * the list of results using a monolithic loop. This avoids
   * stack overflow errors for most grammars.
   */
  override def many[A](p: Parser[A]): Parser[List[A]] =
    s => {
      val (optData, state) = s.readPartialData()
      val partialData = optData.map(_.asInstanceOf[ManyPartialData[A]]) getOrElse NoManyPartialData
      val buf = new collection.mutable.ListBuffer[A]
      buf appendAll partialData.results
      def go(p: Parser[A], currentState: ParseState, offset: Int): Result[List[A]] = {
        println("Many at " + currentState.loc.offset)
        p(currentState) match {
          case Success(a,n) =>
            buf += a
            println("Many: found " + a)
            go(p, currentState.clearPartialData().advanceBy(n), offset + n)
          case f@Failure(e,true,_) => f.appendPartialData(ManyPartialData(buf.toList))
          case Failure(e,_,_) => Success(buf.toList, offset)
        }
      }
      go(p, state, 0)
    }
}

