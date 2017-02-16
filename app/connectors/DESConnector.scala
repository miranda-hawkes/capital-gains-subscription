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

import audit.Logging
import javax.inject.{Inject, Singleton}

import common.AuditConstants._
import config.{ApplicationConfig, WSHttp}
import models._
import play.api.Logger
import play.api.http.Status._
import play.api.libs.json.{JsValue, Json, Writes}
import uk.gov.hmrc.play.config.ServicesConfig
import uk.gov.hmrc.play.http._
import uk.gov.hmrc.play.http.logging.Authorization

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

sealed trait DesResponse

case class SuccessDesResponse(response: JsValue) extends DesResponse

case object NotFoundDesResponse extends DesResponse

case object DesErrorResponse extends DesResponse

case class InvalidDesRequest(message: String) extends DesResponse

case object DuplicateDesResponse extends DesResponse

@Singleton
class DESConnector @Inject()(appConfig: ApplicationConfig, logger: Logging) extends HttpErrorFunctions with ServicesConfig {

  lazy val serviceUrl: String = appConfig.baseUrl("des")
  lazy val serviceContext: String = appConfig.desContextUrl

  val environment = "test"
  val token = "des"
  val obtainSAPUrl = "/register"
  val obtainSAPUrlGhost = "/non-resident/individual/register"
  val urlHeaderEnvironment = "??? see srcs, found in config"
  val urlHeaderAuthorization = "??? same as above"
  val http: HttpGet with HttpPost with HttpPut = WSHttp

  def subscribe(submissionModel: Any)(implicit hc: HeaderCarrier): Future[DesResponse] = {

    submissionModel match {
      case individual: SubscribeIndividualModel =>
        Logger.warn("Made a post request to the stub with an individual subscribers sap of " + individual.sap)

        val requestUrl: String = s"$serviceUrl$serviceContext/individual/${individual.sap}/subscribe"
        val response = cPOST(requestUrl, Json.toJson(individual))
        val auditMap: Map[String, String] = Map("Safe Id" -> individual.sap, "Url" -> requestUrl)
        handleResponse(response, auditMap, individual.sap)

      case company: CompanySubmissionModel =>
        Logger.warn("Made a post request to the stub with a company subscribers sap of " + company.sap.get)

        val requestUrl: String = s"$serviceUrl$serviceContext/non-resident/organisation/subscribe"
        val response = cPOST(requestUrl, Json.toJson(company))
        val auditMap: Map[String, String] = Map("Safe Id" -> company.sap.get, "Url" -> requestUrl)
        handleResponse(response, auditMap, company.sap.get)
    }
  }

  def handleResponse(response: Future[HttpResponse], auditMap: Map[String, String], reference: String)(implicit hc: HeaderCarrier): Future[DesResponse] = {
    response map { r =>
      r.status match {
        case OK =>
          Logger.info(s"Successful DES submission for $reference")
          logger.audit(transactionDESSubscribe, auditMap, eventTypeSuccess)
          SuccessDesResponse(r.json)
        case CONFLICT =>
          Logger.warn("Error Conflict: SAP Number already in existence")
          logger.audit(transactionDESSubscribe, conflictAuditMap(auditMap, r), eventTypeConflict)
          DuplicateDesResponse
        case ACCEPTED =>
          Logger.info(s"Accepted DES submission for $reference")
          logger.audit(transactionDESSubscribe, auditMap, eventTypeSuccess)
          SuccessDesResponse(r.json)
        case BAD_REQUEST =>
          val message = (r.json \ "reason").as[String]
          Logger.warn(s"Error with the request $message")
          logger.audit(transactionDESSubscribe, failureAuditMap(auditMap, r), eventTypeFailure)
          InvalidDesRequest(message)
      }
    } recover {
      case _: NotFoundException =>
        Logger.warn(s"Not found for $reference")
        logger.audit(transactionDESSubscribe, auditMap, eventTypeNotFound)
        NotFoundDesResponse
      case _: InternalServerException =>
        Logger.warn(s"Internal server error for $reference")
        logger.audit(transactionDESSubscribe, auditMap, eventTypeInternalServerError)
        DesErrorResponse
      case _: BadGatewayException =>
        Logger.warn(s"Bad gateway status for $reference")
        logger.audit(transactionDESSubscribe, auditMap, eventTypeBadGateway)
        DesErrorResponse
      case ex: Exception =>
        Logger.warn(s"Exception of ${ex.toString} for $reference")
        logger.audit(transactionDESSubscribe, auditMap, eventTypeGeneric)
        DesErrorResponse
    }
  }

  implicit val httpRds = new HttpReads[HttpResponse] {
    def read(http: String, url: String, res: HttpResponse): HttpResponse = customDESRead(http, url, res)
  }

  @inline
  private def cPOST[I, O](url: String, body: I, headers: Seq[(String, String)] = Seq.empty)(implicit wts: Writes[I], rds: HttpReads[O], hc: HeaderCarrier) = {
    http.POST[I, O](url, body, headers)(wts = wts, rds = rds, hc = createHeaderCarrier(hc))
  }

  @inline
  private def cGET[O](url: String, headers: Seq[(String, String)] = Seq.empty)(implicit rds: HttpReads[O], hc: HeaderCarrier) = {
    http.GET[O](url, headers)(rds, hc = createHeaderCarrier(hc))
  }

  private def createHeaderCarrier(headerCarrier: HeaderCarrier): HeaderCarrier = {
    headerCarrier.
      withExtraHeaders("Environment" -> urlHeaderEnvironment).
      copy(authorization = Some(Authorization(urlHeaderAuthorization)))
  }

  private def conflictAuditMap(auditMap: Map[String, String], response: HttpResponse) =
    auditMap ++ Map("Conflict reason" -> response.body, "Status" -> response.status.toString)

  private def failureAuditMap(auditMap: Map[String, String], response: HttpResponse) =
    auditMap ++ Map("Failure reason" -> response.body, "Status" -> response.status.toString)

  def obtainSAP(registerIndividualModel: RegisterIndividualModel)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[DesResponse] = {
    val requestUrl = s"$serviceUrl$serviceContext/individual/${registerIndividualModel.nino}$obtainSAPUrl"
    val jsonNino = Json.toJson(registerIndividualModel)
    val response = cPOST(requestUrl, jsonNino)
    val auditMap: Map[String, String] = Map("Nino" -> registerIndividualModel.nino.nino, "Url" -> requestUrl)

    Logger.warn("Made a post request to the stub with a url of " + requestUrl)
    response map {
      r =>
        r.status match {
          case OK =>
            Logger.info("SuccessTransactionDESObtainSAP number")
            logger.audit(transactionDESObtainSAP, auditMap, eventTypeSuccess)
            SuccessDesResponse(r.json)
          case ACCEPTED =>
            Logger.info("AcceptTransactionDESObtainSAP number")
            logger.audit(transactionDESObtainSAP, auditMap, eventTypeSuccess)
            SuccessDesResponse(r.json)
          case CONFLICT =>
            Logger.warn("Error Conflict: SAP Number already in existence")
            logger.audit(transactionDESObtainSAP, conflictAuditMap(auditMap, r), eventTypeConflict)
            DuplicateDesResponse
          case BAD_REQUEST =>
            val message = (r.json \ "reason").as[String]
            Logger.warn(s"Error with the request $message")
            logger.audit(transactionDESObtainSAP, failureAuditMap(auditMap, r), eventTypeFailure)
            InvalidDesRequest(message)
        }
    } recover {
      case _: NotFoundException =>
        Logger.warn("Not found exception transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAP, auditMap, eventTypeNotFound)
        NotFoundDesResponse
      case _: InternalServerException =>
        Logger.warn("Internal server error transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAP, auditMap, eventTypeInternalServerError)
        DesErrorResponse
      case _: BadGatewayException =>
        Logger.warn("Bad gateway status transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAP, auditMap, eventTypeBadGateway)
        DesErrorResponse
      case ex: Exception =>
        Logger.warn(s"Exception of ${ex.toString} transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAP, auditMap, eventTypeGeneric)
        DesErrorResponse
    }
  }

  def obtainSAPGhost(userFactsModel: UserFactsModel)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[DesResponse] = {
    val requestUrl: String = s"$serviceUrl$serviceContext$obtainSAPUrlGhost"
    val jsonFullDetails = Json.toJson(userFactsModel)
    val response = cPOST(requestUrl, jsonFullDetails)
    val auditMap: Map[String, String] = Map("Full details" -> userFactsModel.toString, "Url" -> requestUrl)
    response map {
      r =>
        r.status match {
          case OK =>
            Logger.info("SuccessTransactionDESObtainSAP number")
            logger.audit(transactionDESObtainSAPGhost, auditMap, eventTypeSuccess)
            SuccessDesResponse(r.json)
          case ACCEPTED =>
            Logger.info("AcceptTransactionDESObtainSAP number")
            logger.audit(transactionDESObtainSAPGhost, auditMap, eventTypeSuccess)
            SuccessDesResponse(r.json)
          case CONFLICT =>
            Logger.warn("Error Conflict: SAP Number already in existence")
            logger.audit(transactionDESObtainSAPGhost, conflictAuditMap(auditMap, r), eventTypeConflict)
            DuplicateDesResponse
          case BAD_REQUEST =>
            val message = (r.json \ "reason").as[String]
            Logger.warn(s"Error with the request $message")
            logger.audit(transactionDESObtainSAPGhost, failureAuditMap(auditMap, r), eventTypeFailure)
            InvalidDesRequest(message)
        }
    } recover {
      case ex: NotFoundException =>
        Logger.warn("Not found exception transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAPGhost, auditMap, eventTypeNotFound)
        NotFoundDesResponse
      case ex: InternalServerException =>
        Logger.warn("Internal server error transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAPGhost, auditMap, eventTypeInternalServerError)
        DesErrorResponse
      case ex: BadGatewayException =>
        Logger.warn("Bad gateway status transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAPGhost, auditMap, eventTypeBadGateway)
        DesErrorResponse
      case ex: Exception =>
        Logger.warn(s"Exception of ${ex.toString} transactionDESObtainSAP number")
        logger.audit(transactionDESObtainSAPGhost, auditMap, eventTypeGeneric)
        DesErrorResponse
    }
  }

  def getExistingSap(registerIndividualModel: RegisterIndividualModel)(implicit hc: HeaderCarrier): Future[DesResponse] = {
    val getSubscriptionUrl = s"$serviceUrl/taxpayers/${registerIndividualModel.nino}/subscription"
    val response = cGET[HttpResponse](getSubscriptionUrl)
    val auditMap: Map[String, String] = Map("Nino" -> registerIndividualModel.nino.nino, "Url" -> getSubscriptionUrl)
    response map {
      r =>
        r.status match {
          case OK =>
            Logger.info("SuccessTransactionDESGetExistingSAP number")
            logger.audit(transactionDESGetExistingSAP, auditMap, eventTypeSuccess)
            SuccessDesResponse(r.json)
          case ACCEPTED =>
            Logger.info("AcceptTransactionDESGetExistingSAP number")
            logger.audit(transactionDESGetExistingSAP, auditMap, eventTypeSuccess)
            SuccessDesResponse(r.json)
          case BAD_REQUEST =>
            val message = (r.json \ "reason").as[String]
            Logger.warn(s"Error with the request $message")
            logger.audit(transactionDESGetExistingSAP, failureAuditMap(auditMap, r), eventTypeFailure)
            InvalidDesRequest(message)
        }
    } recover {
      case ex: NotFoundException =>
        Logger.warn("Not found exception transactionDESGetExistingSAP number")
        logger.audit(transactionDESGetExistingSAP, auditMap, eventTypeNotFound)
        NotFoundDesResponse
      case ex: InternalServerException =>
        Logger.warn("Internal server error transactionDESGetExistingSAP number")
        logger.audit(transactionDESGetExistingSAP, auditMap, eventTypeInternalServerError)
        DesErrorResponse
      case ex: BadGatewayException =>
        Logger.warn("Bad gateway status transactionDESGetExistingSAP number")
        logger.audit(transactionDESGetExistingSAP, auditMap, eventTypeBadGateway)
        DesErrorResponse
      case ex: Exception =>
        Logger.warn(s"Exception of ${ex.toString} transactionDESGetExistingSAP number")
        logger.audit(transactionDESGetExistingSAP, auditMap, eventTypeGeneric)
        DesErrorResponse
    }
  }

  private[connectors] def customDESRead(http: String, url: String, response: HttpResponse) = {
    response.status match {
      case BAD_REQUEST => response
      case NOT_FOUND => throw new NotFoundException("DES returned a Not Found status")
      case CONFLICT => response
      case INTERNAL_SERVER_ERROR => throw new InternalServerException("DES returned an internal server error")
      case BAD_GATEWAY => throw new BadGatewayException("DES returned an upstream error")
      case _ => handleResponse(http, url)(response)
    }
  }
}
