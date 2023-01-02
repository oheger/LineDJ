/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.io.parser

import de.oliver_heger.linedj.io.parser.ParserTypes._

import scala.reflect.ClassTag
import scala.util.matching.Regex

/**
  * Defines the data types used by the parser implementation.
  */
object ParserTypes {

  /** A parser is a kind of state action that can fail. */
  type Parser[+A] = ParseState => Result[A]

  /**
    * A class representing the current state of the parsing process. An
    * instance of this class is used as input for a parser.
    *
    * @param loc the current location in the input
    * @param partialData information about a partial parse result
    * @param lastChunk a flag whether this is the last chunk
    */
  case class ParseState(loc: Location, partialData: List[Any] = Nil, lastChunk: Boolean = true) {
    def advanceBy(numChars: Int): ParseState =
      copy(loc = loc.copy(offset = loc.offset + numChars))
    def input: String = loc.input.substring(loc.offset)
    def slice(n: Int): String = loc.input.substring(loc.offset, loc.offset + n)

    /**
      * Obtains information about a partial parse result. This information is
      * stored to continue a parse operation that failed because the end of the
      * current chunk was reached. When the next chunk becomes available
      * parsing should continue at the very same position in the same state.
      * If no partial data is available (e.g. because this is the first chunk
      * or all partial data has already been consumed), ''None'' is returned.
      *
      * @param tag an implicit class tag
      * @tparam T the type of the partial data to be returned
      * @return the extracted partial data and the updated state
      */
    def readPartialData[T](implicit tag: ClassTag[T]): (Option[T], ParseState) = partialData match {
      case h :: t =>
        (Some(h.asInstanceOf[T]), copy(partialData = t))
      case _ => (None, this)
    }

    /**
      * Clears information about partial parse results. This method is called
      * to indicate that a parse operation could be continued successfully on
      * the next chunk. Then parsers invoked later in the chain do not have to
      * deal with this data.
      *
      * @return the updated state
      */
    def clearPartialData(): ParseState =
      if (partialData.isEmpty) this
      else copy(partialData = Nil)
  }

  /**
    * The result of a parser invocation.
    *
    * @tparam A the type of the result produced by the parser
    */
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

    /**
      * Adds information about a failed parse operation to this result if it is
      * a failure. A parse operation may fail even on correct data if the end
      * of a chunk is reached. In this case, information has to be added to the
      * failure result that makes it possible to continue parsing with the next
      * chunk
      *
      * @param data information to continue parsing
      * @return the updated result
      */
    def appendPartialData(data: => Any): Result[A] = this match {
      case Failure(e, c, d) => Failure(e, c, data :: d)
      case _ => this
    }

    /**
      * Clears all information to continue a failed parse operation.
      *
      * @return the updated result
      */
    def clearPartialData(): Result[A] = this match {
      case Failure(e, c, _) => Failure(e, c, Nil)
      case _ => this
    }
  }
  case class Success[+A](get: A, length: Int) extends Result[A]
  case class Failure(get: ParseError, isCommitted: Boolean, partialData: List[Any]) extends Result[Nothing]

  /** Returns -1 if s1.startsWith(s2), otherwise returns the
    * first index where the two strings differed. If s2 is
    * longer than s1, returns s1.length. */
  def firstNonMatchingIndex(s1: String, s2: String, offset: Int): Int = {
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
object ParserImpl extends ChunkParser[Parser, Result, Failure] {

  /**
    * A data class representing a partial result of a many operation. An
    * instance is created if a many operation fails; if this is due to the
    * end of the current chunk, the operation can be continued with the next
    * chunk.
    *
    * @param results the results collected so far by many
    * @tparam A the type of results produced by the parser
    */
  case class ManyPartialData[+A](results: List[A])

  /**
    * A data class representing a partial result of a flatMap operation. An
    * instance is created if a flatMap operation fails; it stores the result
    * obtained by the parser before the mapping function is invoked. This is
    * sufficient to continue the operation when another chunk of data becomes
    * available.
    *
    * @param result the parser result
    * @tparam A the type of the result
    */
  case class FlatMapPartialData[A](result: Option[Result[A]]) {
    /**
      * Advances the specified state by the given index if this is allowed in
      * the current state. If the represented result stems from a previous
      * chunk, the current position must not be advanced. Otherwise, it has to
      * be advanced, and the partial data must be cleared.
      *
      * @param s the state
      * @param n the number of chars to advance
      * @return the updated state
      */
    def advanceAndUpdateState(s: ParseState, n: Int): ParseState =
      if (canAdvance) s.clearPartialData() advanceBy n
      else s

    /**
      * Advances the specified result by the given index if this is allowed in
      * the current state. Like ''advanceState()'', but for the result after
      * invocation of the mapping function.
      *
      * @param r the result
      * @param n the number of chars to advance
      * @tparam R the type of the result
      * @return the updated result
      */
    def advanceResult[R](r: Result[R], n: Int): Result[R] =
      if (canAdvance) r advanceSuccess n
      else r

    /**
      * Returns a flag whether in the current state the current position can be
      * advanced.
      *
      * @return a flag whether advancing is possible
      */
    private def canAdvance: Boolean =
      result.isEmpty
  }

  /**
    * A data class representing a partial result of an or operation. An
    * instance is created if the tree beneath the or could not be followed
    * successfully. Then the affected parser is stored (the first one or the
    * second one). When the parsing operation continues with the next chunk the
    * corresponding parser can be directly invoked again.
    *
    * @param parserIndex the index of the active parser
    */
  case class OrPartialData(parserIndex: Int) {
    /**
      * Returns the first parser to invoke in an or operation. Which one this
      * is depends on the state of the overall parsing process.
      *
      * @param p   the first parser passed to ''or''
      * @param alt the alternative parser passed to ''or''
      * @tparam A the result type of the parser
      * @return the first parser to invoke
      */
    def firstParser[A](p: Parser[A], alt: => Parser[A]): Parser[A] =
      if (parserIndex == 1) p else alt

    /**
      * Returns the second parser to invoke in an or operation. This is
      * analogous to ''firstParser()'', but this method is used if the first
      * invocation fails. Then the alternative parse gets a chance.
      *
      * @param p   the first parser passed to ''or''
      * @param alt the alternative parser passed to ''or''
      * @tparam A the result type of the parser
      * @return the first parser to invoke
      */
    def alternativeParser[A](p: Parser[A], alt: => Parser[A]): Parser[A] =
      if (parserIndex == 1) alt else p
  }

  /**
    * Constant representing an empty partial result for a many operation.
    */
  val EmptyManyPartialData = ManyPartialData(Nil)

  /** Constant for an empty partial data for a flatMap operation. */
  private val EmptyFlatMapPartialData = FlatMapPartialData(None)

  /** Partial result data for ''or'' representing the first branch. */
  private val FirstParser = OrPartialData(1)

  /** Partial result data for ''or'' representing the second branch. */
  private val AlternativeParser = OrPartialData(2)

  /**
    * Constant for a ''Failure'' object that does not contain any data. This is
    * used as a fallback when dealing with the first chunk of a chunk-wise
    * parse operation.
    */
  private val EmptyFailure = Failure(get = ParseError(), isCommitted = false, partialData = Nil)

  def runChunk[A](p: Parser[A])(nextChunk: String, lastChunk: Boolean,
                                optFailure: Option[Failure] = None): Result[A] = {
    val failure = optFailure getOrElse EmptyFailure
    val nextInput = failure.get.latestLoc.map(l => l.input.substring(l.offset)).getOrElse("") +
      nextChunk
    p(ParseState(Location(nextInput), lastChunk = lastChunk, partialData = failure.partialData))
  }

  // consume no characters and succeed with the given value
  def succeed[A](a: A): Parser[A] = s => Success(a, 0)

  def or[A](p: Parser[A], p2: => Parser[A]): Parser[A] =
    s => {
      lazy val alternative = p2
      val (optData, state) = s.readPartialData[OrPartialData]
      val orData = optData getOrElse FirstParser
      orData.firstParser(p, alternative)(state) match {
        case Failure(e, false, _) => orData.alternativeParser(p, alternative)(state.clearPartialData()) match {
          case f@Failure(er, false, _) => f.clearPartialData()
          case r => r.appendPartialData(AlternativeParser)
        }
        case r => r.appendPartialData(FirstParser) // committed failure or success skips running `p2`
      }
    }

  def flatMap[A, B](f: Parser[A])(g: A => Parser[B]): Parser[B] =
    s => {
      val (optData, state) = s.readPartialData[FlatMapPartialData[A]]
      val optResult = optData.flatMap(_.result)
      val data = optData getOrElse EmptyFlatMapPartialData
      val result = data.result getOrElse f(state)
      result match {
        case Success(a, n) =>
          val mapResult = g(a)(data.advanceAndUpdateState(state, n))
          data.advanceResult(mapResult, n)
            .addCommit(n != 0)
            .appendPartialData(FlatMapPartialData(Some(result)))
        case f@Failure(_, _, _) =>
          f.appendPartialData(EmptyFlatMapPartialData)
      }
    }

  def string(w: String): Parser[String] = {
    val msg = "'" + w + "'"
    s => {
      val i = firstNonMatchingIndex(s.loc.input, w, s.loc.offset)
      if (i == -1) {
        // they matched
        Success(w, w.length)
      }
      else {
        Failure(s.loc.toError(msg), i != 0, Nil)
      }
    }
  }

  /* note, regex matching is 'all-or-nothing':
   * failures are uncommitted */
  def regex(r: Regex): Parser[String] = {
    val msg = "regex " + r
    s => {
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
      val (optData, state) = s.readPartialData[ManyPartialData[A]]
      val partialData = optData getOrElse EmptyManyPartialData
      val buf = new collection.mutable.ListBuffer[A]
      buf appendAll partialData.results
      def go(p: Parser[A], currentState: ParseState, offset: Int): Result[List[A]] = {
        p(currentState) match {
          case Success(a,n) =>
            buf += a
            go(p, currentState.clearPartialData().advanceBy(n), offset + n)
          case f@Failure(e,true,_) => f.appendPartialData(ManyPartialData(buf.toList))
          case Failure(e,_,_) => Success(buf.toList, offset)
        }
      }
      go(p, state, 0)
    }
}

