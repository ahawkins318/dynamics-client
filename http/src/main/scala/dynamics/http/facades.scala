// Copyright (c) 2017 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics
package http

import scala.scalajs.js
import js.UndefOr
import js.annotation._

/** User provided configuration informmation. */
trait ConnectionInfo extends js.Object {
  var tenant: UndefOr[String]               = js.undefined
  var username: UndefOr[String]             = js.undefined
  var password: UndefOr[String]             = js.undefined
  var applicationId: UndefOr[String]        = js.undefined
  var dataUrl: UndefOr[String]              = js.undefined
  var acquireTokenResource: UndefOr[String] = js.undefined
  var authorityHostUrl: UndefOr[String]     = js.undefined
  var renewalFraction: UndefOr[Int]         = js.undefined
  var secret: UndefOr[String]         = js.undefined
}

/**
  * Use this when you *only* care about the `@odata.nextLink` link.
  */
@js.native
trait NextLinkResponse[A] extends js.Object {
  @JSName("@odata.nextLink")
  val nextLink: UndefOr[String] = js.native
}

/**
  * General response envelope when an array of values is returned in
  * "values".. Use A=_ if you do not care about the values.  This is only used
  * to process return bodies and find the "value" array that may, or may not be
  * there. You get this when querying for a list or when navigating to a
  * collection valued property. If you use $expand on a collection value
  * property it is listed under its attribute name on the target entity and is
  * *not* under the "value" fieldname.
  */
@js.native
trait ValueArrayResponse[A <: js.Any] extends NextLinkResponse[A] {
  val value: UndefOr[js.Array[A]] = js.native
}

/**
  * The shape when using navigation properties to a single value it is returned
  * in the fieldname "value".
  */
@js.native
trait SingleValueResponse[A <: js.Any] extends js.Object {
  val value: js.UndefOr[A] = js.native
}
