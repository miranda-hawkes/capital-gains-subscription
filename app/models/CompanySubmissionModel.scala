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

package models

import play.api.libs.json.{JsValue, Json, OFormat}

case class CompanySubmissionModel(
                                   sap: Option[String],
                                   contactAddress: Option[CompanyAddressModel],
                                   registeredAddress: Option[CompanyAddressModel]
                                 ) {
  require(CompanySubmissionModel.validateSAP(sap), s"SAP:$sap is not valid.")
}

object CompanySubmissionModel {
  implicit val formats: OFormat[CompanySubmissionModel] = Json.format[CompanySubmissionModel]

  implicit val toEtmpSubscription: CompanySubmissionModel => JsValue = model => {
    Json.obj(
      "addressDetail" -> Json.obj(
        "line1" -> model.registeredAddress.get.addressLine1.get,
        "line2" -> model.registeredAddress.get.addressLine2.get,
        "line3" -> model.registeredAddress.get.addressLine3,
        "line4" -> model.registeredAddress.get.addressLine4,
        "postalCode" -> model.registeredAddress.get.postCode,
        "countryCode" -> model.registeredAddress.get.country.get
      )
    )
  }

  def validateSAP(sap: Option[String]): Boolean = {
    sap match {
      case Some(data) => data.length == 15
      case _ => true
    }
  }
}

