package im.actor.server.session

import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import im.actor.api.rpc._
import im.actor.api.rpc.auth.{ RequestStartPhoneAuth, ResponseStartPhoneAuth }
import im.actor.api.rpc.codecs.RequestCodec
import im.actor.server.ActorSpecification
import im.actor.server.mtproto.protocol._

import scala.concurrent.duration._
import scala.util.Random

final class SessionResendLargeSpec extends BaseSessionSpec(
  ActorSpecification.createSystem(ConfigFactory.parseString(
    """
      |session {
      |  resend {
      |    ack-timeout = 5 seconds
      |    max-resend-size = 1 KiB
      |    max-buffer-size = 1 KiB
      |  }
      |}
    """.stripMargin
  ))
) {
  behavior of "Session's ReSender with max-resend-size set to 0 KiB"

  it should "resend UnsentResponse instead of the full response" in Sessions().e1
  it should "kill session on resend buffer overflow" in Sessions().e2

  case class Sessions() {
    def e1() = {
      implicit val probe = TestProbe()

      val authId = createAuthId()
      val sessionId = Random.nextLong()
      val requestMessageId = Random.nextLong()

      val encodedRequest = RequestCodec.encode(Request(RequestStartPhoneAuth(
        phoneNumber = 75553333333L,
        appId = 1,
        apiKey = "apiKey",
        deviceHash = Random.nextLong.toBinaryString.getBytes,
        deviceTitle = "Specs Has You",
        timeZone = None,
        preferredLanguages = Vector.empty
      ))).require
      sendMessageBox(authId, sessionId, sessionRegion.ref, requestMessageId, ProtoRpcRequest(encodedRequest))

      expectNewSession(authId, sessionId, requestMessageId)

      expectRpcResult(authId, sessionId, sendAckAt = None) should matchPattern {
        case RpcOk(_: ResponseStartPhoneAuth) ⇒
      }

      // We didn't send Ack
      Thread.sleep(5000)

      val messageBox = expectMessageBox()
      messageBox.body should matchPattern {
        case UnsentResponse(_, rqMessageId, length) if rqMessageId == requestMessageId && length > 0 ⇒
      }

      val msgId = Random.nextLong()
      sendMessageBox(authId, sessionId, sessionRegion.ref, msgId, RequestResend(messageBox.body.asInstanceOf[UnsentResponse].messageId))

      expectRpcResult(authId, sessionId, sendAckAt = None, expectAckFor = Set(msgId)) should matchPattern {
        case RpcOk(_: ResponseStartPhoneAuth) ⇒
      }

      expectNoMsg(6.seconds)
    }

    def e2() = {
      val watchProbe = TestProbe()

      val authId = createAuthId()
      val sessionId = Random.nextLong()
      val session = system.actorOf(Session.props, s"${authId}_$sessionId")
      watchProbe watch session

      val encodedRequest = RequestCodec.encode(Request(RequestStartPhoneAuth(
        phoneNumber = 75553333333L,
        appId = 1,
        apiKey = "apiKey",
        deviceHash = Random.nextLong.toBinaryString.getBytes,
        deviceTitle = "Specs Has You",
        timeZone = None,
        preferredLanguages = Vector.empty
      ))).require

      for (_ ← 1 to 100)
        TestProbe().send(session, handleMessageBox(Random.nextLong(), ProtoRpcRequest(encodedRequest)))

      watchProbe.expectTerminated(session)
    }
  }

}