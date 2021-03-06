// Copyright (c) 2017 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics
package common

import scala.scalajs.js
import js._
import js.|
import JSConverters._
import io.scalajs.nodejs._
import scala.concurrent._
import io.scalajs.util.PromiseHelper.Implicits._
import fs2._
import cats._
import cats.data._
import cats.implicits._
import cats.arrow.FunctionK
import cats.effect._
import io.scalajs.npm.chalk._
import js.Dynamic.{literal => jsobj}

import Utils._

final case class StreamOps[F[_], O](s: Stream[F, O]) {
  def vectorChunkN(n: Int): Stream[F, Vector[O]] = s.segmentN(n).map(_.force.toVector)
  def groupBy[O2](f: O => O2)(implicit eq: Eq[O2]): Stream[F, (O2, Vector[O])] =
    s.groupAdjacentBy(f).map(p => (p._1, p._2.force.toVector))
}

trait StreamSyntax {
  implicit def streamToStream[F[_], O](s: Stream[F, O]): StreamOps[F, O] = StreamOps(s)
}

/**
  * It is common in interop code to model a value as A or null but not undefined.
  */
final case class OrNullOps[A <: js.Any](a: A | Null) {

  /** Convert an A|Null to a well formed option. */
  @inline def toNonNullOption: Option[A] =
    if (a.asInstanceOf[js.Any] == null) Option.empty[A]
    else Some(a.asInstanceOf[A])

  /** If Null, then false, else true. */
  @inline def toTruthy: Boolean =
    if (js.DynamicImplicits.truthValue(a.asInstanceOf[js.Dynamic])) true
    else false
}

trait OrNullSyntax {
  implicit def orNullSyntax[A <: js.Any](a: A | Null): OrNullOps[A] = OrNullOps[A](a)
}

final case class JsAnyOps(a: js.Any) {
  @inline def asJsObj: js.Object          = a.asInstanceOf[js.Object]
  @inline def asDyn: js.Dynamic           = a.asInstanceOf[js.Dynamic]
  @inline def asString: String            = a.asInstanceOf[String]
  @inline def asNumer: Number             = a.asInstanceOf[Number]
  @inline def asInt: Int                  = a.asInstanceOf[Int]
  @inline def asDouble: Double            = a.asInstanceOf[Double]
  @inline def asBoolean: Boolean          = a.asInstanceOf[Boolean]
  @inline def asJsArray[A]: js.Array[A]   = a.asInstanceOf[js.Array[A]]
  @inline def toJson: String              = js.JSON.stringify(a)
  @inline def asUndefOr[A]: js.UndefOr[A] = a.asInstanceOf[js.UndefOr[A]]
  @inline def toNonNullOption[T <: js.Any]: Option[T] = {
    // also defined in react package, repeated here
    if (js.isUndefined(a) || a == null) None
    else Option(a.asInstanceOf[T])
  }
  @inline def toTruthy: Boolean =
    if (js.DynamicImplicits.truthValue(a.asInstanceOf[js.Dynamic])) true
    else false
}

trait JsAnySyntax {
  implicit def jsAnyOpsSyntax(a: js.Any) = new JsAnyOps(a)
}

final case class JsObjectOps(o: js.Object) {
  @inline def asDict[A]                       = o.asInstanceOf[js.Dictionary[A]]
  @inline def asAnyDict                       = o.asInstanceOf[js.Dictionary[js.Any]]
  @inline def asDyn                           = o.asInstanceOf[js.Dynamic]
  @inline def asUndefOr[A]: js.UndefOr[A]     = o.asInstanceOf[js.UndefOr[A]]
  @inline def combine(that: js.Object)        = merge(o, that)
  @inline def combine(that: js.Dictionary[_]) = merge(o, that.asInstanceOf[js.Object])
}

final case class JsDictionaryOps(o: js.Dictionary[_]) {
  @inline def asJsObj                     = o.asInstanceOf[js.Object]
  @inline def asDyn                       = o.asInstanceOf[js.Dynamic]
  @inline def asUndefOr[A]: js.UndefOr[A] = o.asInstanceOf[js.UndefOr[A]]
  @inline def combine(that: js.Dictionary[_]) =
    merge(o.asInstanceOf[js.Object], that.asInstanceOf[js.Object]).asInstanceOf[js.Dictionary[_]]
}

trait JsObjectSyntax {
  implicit def jsObjectOpsSyntax(a: js.Object)           = new JsObjectOps(a)
  implicit def jsDictonaryOpsSyntax(a: js.Dictionary[_]) = new JsDictionaryOps(a)
}

final case class JsUndefOrStringOps(a: UndefOr[String]) {
  def orEmpty: String = a.getOrElse("")
}

/** Not sure this is really going to do much for me. */
final case class JsUndefOrOps[A](a: UndefOr[A]) {
  @inline def isNull          = a == null
  @inline def isEmpty         = isNull || !a.isDefined
  @inline def toNonNullOption = if (a == null || a.isEmpty) None else a.toOption
  @inline def toStringJs      = a.asInstanceOf[js.Any].toString()
  @inline def toTruthy: Boolean =
    if (js.DynamicImplicits.truthValue(a.asInstanceOf[js.Dynamic])) true
    else false
}

trait JsUndefOrSyntax {
  implicit def jsUndefOrOpsSyntax[A](a: UndefOr[A])   = JsUndefOrOps(a)
  implicit def jsUndefOrStringOps(a: UndefOr[String]) = JsUndefOrStringOps(a)
}

final case class JsDynamicOps(val jsdyn: js.Dynamic) {
  @inline def asString: String        = jsdyn.asInstanceOf[String]
  @inline def asInt: Int              = jsdyn.asInstanceOf[Int]
  @inline def asArray[A]: js.Array[A] = jsdyn.asInstanceOf[js.Array[A]]
  @inline def asBoolean: Boolean      = jsdyn.asInstanceOf[Boolean]

  /** @deprecated use asJsObj */
  @inline def asJSObj: js.Object          = jsdyn.asInstanceOf[js.Object]
  @inline def asJsObj: js.Object          = jsdyn.asInstanceOf[js.Object]
  @inline def asDict[A]: js.Dictionary[A] = jsdyn.asInstanceOf[js.Dictionary[A]]
  @inline def asUndefOr[A]: js.UndefOr[A] = jsdyn.asInstanceOf[js.UndefOr[A]]
  @inline def asJsObjSub[A <: js.Object]  = jsdyn.asInstanceOf[A] // assumes its there!
  @inline def asJsArray[A <: js.Object]   = jsdyn.asInstanceOf[js.Array[A]]
  @inline def toOption[T <: js.Object]: Option[T] =
    if (js.DynamicImplicits.truthValue(jsdyn)) Some(jsdyn.asInstanceOf[T])
    else None
  @inline def toNonNullOption[T <: js.Object]: Option[T] = JsUndefOrOps(asUndefOr).toNonNullOption
  @inline def combine(that: js.Dynamic)                  = mergeJSObjects(jsdyn, that)
  @inline def toTruthy: Boolean =
    if (js.DynamicImplicits.truthValue(jsdyn)) true
    else false
}

trait JsDynamicSyntax {
  implicit def jsDynamicOpsSyntax(jsdyn: js.Dynamic) = JsDynamicOps(jsdyn)
}

object NPMTypes {
  type JSCallbackNPM[A] = js.Function2[io.scalajs.nodejs.Error, A, scala.Any] => Unit
  type JSCallback[A]    = js.Function2[js.Error, A, scala.Any] => Unit

  /** This does not work as well as I thought it would... */
  def callbackToIO[A](f: JSCallbackNPM[A])(implicit e: ExecutionContext): IO[A] = JSCallbackOpsNPM(f).toIO
}

import NPMTypes._

final case class JSCallbackOpsNPM[A](val f: JSCallbackNPM[A]) {

  import scala.scalajs.runtime.wrapJavaScriptException

  /** Convert a standard (err, a) callback to a IO. */
  def toIO(implicit e: ExecutionContext) =
    IO.async { (cb: (Either[Throwable, A] => Unit)) =>
      f((err, a) => {
        if (err == null || js.isUndefined(err)) cb(Right(a))
        else cb(Left(wrapJavaScriptException(err)))
      })
    }
}

trait JSCallbackSyntaxNPM {
  implicit def jsCallbackOpsSyntaxNPM[A](f: JSCallbackNPM[A])(implicit s: ExecutionContext) = JSCallbackOpsNPM(f)
}

trait JsObjectInstances {

  /** Show JSON in its rawness form. Use PrettyJson. for better looking JSON. */
  implicit def showJsObject[A <: js.Object] = Show.show[A] { obj =>
    val sb = new StringBuilder()
    sb.append(JSON.stringify(obj)) //Utils.pprint(obj))
    sb.toString
  }

  /**
    * Monoid for js.Object, which is really just a dictionary. Use |+| or "combine" to
    * combine.
    */
  implicit val jsObjectMonoid: Monoid[js.Object] =
    new Monoid[js.Object] {
      def combine(lhs: js.Object, rhs: js.Object): js.Object = {
        Utils.merge[js.Object](lhs, rhs)
      }

      def empty: js.Object = new js.Object()
    }
}

trait IOInstances {
  val ioToIOFunctionK = FunctionK.id[IO]

  implicit val ioToIO: IO ~> IO = new (IO ~> IO) {
    override def apply[A](ioa: IO[A]): IO[A] = ioa
  }
}

trait JsPromiseInstances {

  /**
    * Natural transformation from js.Promise to IO.
    */
  implicit def jsPromiseToIO(implicit e: ExecutionContext): js.Promise ~> IO =
    new (js.Promise ~> IO) {
      override def apply[A](p: js.Promise[A]): IO[A] =
        common.syntax.jsPromise.RichPromise(p).toIO
    }
}

trait JsPromiseSyntax {

  /** Convert a js.Promise to a IO. */
  implicit class RichPromise[A](p: js.Promise[A]) {
    def toIO(implicit ec: ExecutionContext): IO[A] = _jsPromiseToIO(p)
  }
}

case class FutureOps[A](val f: Future[A])(implicit ec: ExecutionContext) {
  def toIO: IO[A] = IO.fromFuture(IO(f))
}

trait FutureSyntax {
  implicit def futureToIO[A](f: Future[A])(implicit ec: ExecutionContext) = FutureOps[A](f)
}

case class IteratorOps[A](val iter: scala.Iterator[A])(implicit ec: ExecutionContext) {

  /** I think this is fs2 now directly: `Stream.fromIterator`. */
  def toFS2Stream[A] = Stream.unfold(iter)(i => if (i.hasNext) Some((i.next, i)) else None)
}

trait IteratorSyntax {
  implicit def toIteratorOps[A](iter: scala.Iterator[A])(implicit ec: ExecutionContext) =
    IteratorOps[A](iter)
}

// Add each individual syntax trait to this
trait AllSyntax
    extends JsDynamicSyntax
    with JSCallbackSyntaxNPM
    with JsUndefOrSyntax
    with JsObjectSyntax
    with JsAnySyntax
    with FutureSyntax
    with IteratorSyntax
    with JsPromiseSyntax
    with StreamSyntax
    with OrNullSyntax

// Add each individal syntax trait to this
object syntax {
  object all           extends AllSyntax
  object jsdynamic     extends JsDynamicSyntax
  object jscallbacknpm extends JSCallbackSyntaxNPM
  object jsundefor     extends JsUndefOrSyntax
  object jsobject      extends JsObjectSyntax
  object jsany         extends JsAnySyntax
  object future        extends FutureSyntax
  object iterator      extends IteratorSyntax

  /** @deprecated, use "jspromise" */
  object jsPromise extends JsPromiseSyntax
  object jspromise extends JsPromiseSyntax
  object stream    extends StreamSyntax
  object ornull    extends OrNullSyntax
}

trait AllInstances extends JsObjectInstances

object instances {
  object all extends AllInstances

  /** @deprecated Use "jsobject" */
  object jsObject extends JsObjectInstances
  object jsobject extends JsObjectInstances

  /** @deprecated. Use "jspromise" */
  object jsPromise extends JsPromiseInstances
  object jspromise extends JsPromiseInstances
  object io        extends IOInstances
}

object implicits extends AllSyntax with AllInstances
