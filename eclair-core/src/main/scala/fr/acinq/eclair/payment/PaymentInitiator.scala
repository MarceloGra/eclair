/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.PaymentsDb
import fr.acinq.eclair.payment.PaymentLifecycle.SendPayment

/**
  * Created by PM on 29/08/2016.
  */
class PaymentInitiator(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef, paymentDb: PaymentsDb) extends Actor with ActorLogging {

  override def receive: Receive = {
    case c: SendPayment =>
      val paymentId = UUID.randomUUID()
      val payFsm = context.actorOf(PaymentLifecycle.props(paymentId, sourceNodeId, router, register, paymentDb))
      payFsm forward c
      sender ! paymentId
  }

}

object PaymentInitiator {
  def props(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef, paymentDb: PaymentsDb) = Props(classOf[PaymentInitiator], sourceNodeId, router, register, paymentDb)
}