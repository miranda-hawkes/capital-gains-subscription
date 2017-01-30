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

package audit

import com.google.inject.{Inject, Singleton}
import common.AuditConstants
import play.api.Logger
import uk.gov.hmrc.play.http.HeaderCarrier

@Singleton
class Logging @Inject()(auditing: Auditing) {

  private def sendDataToSplunk(transactionName: String, detail: Map[String, String], eventType: String)(implicit hc: HeaderCarrier): Unit = {
    Logger.debug(AuditConstants.splunk + splunkToLogger(transactionName, detail, eventType))
    auditing.sendDataEvent(
      transactionName = transactionName,
      detail = detail,
      eventType = eventType
    )
  }

  private[audit] def splunkToLogger(transactionName: String, detail: Map[String, String], eventType: String): String = {
    s"${if (eventType.nonEmpty) eventType + "\n" else ""}$transactionName\n$detail"
  }

  def audit(transactionName: String, detail: Map[String, String], eventType: String)(implicit hc: HeaderCarrier): Unit = {
    sendDataToSplunk(transactionName, detail, eventType)
  }
}
