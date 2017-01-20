/*
 * Copyright 2017 HM Revenue & Customs
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

package connectors

import java.util.UUID

import helpers.TestHelper._
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfter
import org.scalatest.mock.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.http.{HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.play.http.ws.{WSGet, WSPost, WSPut}
import uk.gov.hmrc.play.test.UnitSpec
import play.api.http.Status._
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.play.http.logging.SessionId

import scala.concurrent.Future

class DESConnectorSpec extends UnitSpec with OneServerPerSuite with MockitoSugar with BeforeAndAfter{

  class MockHttp extends WSGet with WSPost with WSPut with HttpAuditing {
    override val hooks = Seq(AuditingHook)
    override def appName = "test"
    override def auditConnector: AuditConnector = ???
  }

  val mockWSHttp = mock[MockHttp]

  object TestDESConnector extends DESConnector {
    override val serviceUrl = "test"
    override val environment = "test"
    override val token = "test"
    override val http = mockWSHttp
  }

  before {
    reset(mockWSHttp)
  }

  "httpRds" should {

    "return the http response when a OK status code is read from the http response" in {
      val response = HttpResponse(OK)
      TestDESConnector.httpRds.read("http://", "testUrl", response) shouldBe response
    }

    "return a not found exception when it reads a NOT_FOUND status code from the http response" in {
      intercept[NotFoundException]{
        TestDESConnector.httpRds.read("http://", "testUrl", HttpResponse(NOT_FOUND))
      }
    }
  }


  "Calling .subscribe" should {

    implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))

    "return success with a valid safeId and ackRef" in {
      when(mockWSHttp.POST[JsValue, HttpResponse](ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())
        (ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).
        thenReturn(Future.successful(HttpResponse(OK, responseJson = Some(Json.obj("Submission" -> dummySubscriptionRequestValid)))))

      val result = await(TestDESConnector.subscribe(dummyValidSafeID, dummySubscriptionRequestValid))

      result shouldBe SuccessDesResponse(Json.obj("Submission" -> dummySubscriptionRequestValid))
    }

    "return success with a conflicted submission" should {
      when(mockWSHttp.POST[JsValue, HttpResponse](ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())
        (ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).
        thenReturn(Future.successful(HttpResponse(CONFLICT, responseJson = Some(Json.obj("Submission" -> dummySubscriptionRequestValid)))))

      val result = await(TestDESConnector.subscribe(dummyValidSafeID, dummySubscriptionRequestValid))

      result shouldBe SuccessDesResponse(Json.obj("Submission" -> dummySubscriptionRequestValid))
    }

    "return a BAD_REQUEST with an invalid safeId and a valid ackRef" should {
      when(mockWSHttp.POST[JsValue, HttpResponse](ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())
        (ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).
        thenReturn(Future.successful(HttpResponse(BAD_REQUEST, responseJson = Some(Json.obj("reason" -> "error")))))

      val result = await(TestDESConnector.subscribe(dummyInvalidSafeID, dummySubscriptionRequestValid))

      result shouldBe InvalidDesRequest("error")
    }

    "return a BAD_REQUEST with a valid safeID and an invalid ackRef" should {
      when(mockWSHttp.POST[JsValue, HttpResponse](ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())
        (ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).
        thenReturn(Future.successful(HttpResponse(BAD_REQUEST, responseJson = Some(Json.obj("reason" -> "error")))))

      val result = await(TestDESConnector.subscribe(dummyValidSafeID, dummySubscriptionRequestBadRequest))

      result shouldBe InvalidDesRequest("error")
    }

    "return a NOT_FOUND error with an ackRef containing 'not found'" should {
      when(mockWSHttp.POST[JsValue, HttpResponse](ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())
        (ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).
        thenReturn(Future.successful(HttpResponse(NOT_FOUND, responseJson = Some(Json.obj("reason" -> "test")))))

      val result = await(TestDESConnector.subscribe(dummyValidSafeID, dummySubscriptionRequestNotFound))

      result shouldBe DesErrorResponse
    }

    "return a SERVICE UNAVAILABLE error with an ackRef containing 'serviceunavailable'" should {

    }

    "return a INTERNAL SERVER ERROR with an ackRef containing 'servererror'" should {

    }

    "return a INTERNAL SERVER ERROR with an ackRef containing 'sapnumbermissing'" should {

    }

    "return a SERVICE UNAVAILABLE ERROR with an ackRef containing 'notprocessed'" should {

    }
  }

  "Calling .register" should {

  }

}
