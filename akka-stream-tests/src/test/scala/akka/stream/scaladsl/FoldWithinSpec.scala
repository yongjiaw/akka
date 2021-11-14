/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.impl.fusing.{FoldWith, FoldWithin}
import akka.stream.testkit.{StreamSpec, TestPublisher, TestSubscriber}
import akka.testkit.{AkkaSpec, ExplicitlyTriggeredScheduler}
import com.typesafe.config.ConfigValueFactory

import scala.concurrent.Await
import scala.concurrent.duration._

class FoldWithSpec extends StreamSpec {

  "split aggregator by size" in {

    val stream    = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val groupSize = 3
    val result = Source(stream)
      .via(
        new FoldWith[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = seq => seq.size >= groupSize,
          harvest = seq => seq
        )
      )
      .runWith(Sink.collection)

    Await.result(result, 10.seconds) should be (stream.grouped(groupSize).toSeq)

  }

  "split aggregator by size and harvest" in {
    val stream    = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val groupSize = 3
    val result = Source(stream)
      .via(
        new FoldWith[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = seq => seq.size >= groupSize,
          harvest = seq => seq :+ -1 // append -1 to output to demonstrate the effect of harvest
        )
      )
      .runWith(Sink.collection)


    Await.result(result, 10.seconds) should be (stream.grouped(groupSize).toSeq.map(seq => seq :+ -1))

  }

  "split aggregator by custom weight condition" in {
    val stream = collection.immutable.Seq(1, 2, 3, 4, 5, 6, 7)
    val weight = 10

    val result = Source(stream)
      .via(
        new FoldWith[Int, (Seq[Int], Int), Seq[Int]](
          seed = i => (Seq(i), i),
          aggregate = (seqAndWeight, i) => (seqAndWeight._1 :+ i, seqAndWeight._2 + i),
          emitOnAgg = seqAndWeight => seqAndWeight._2 >= weight,
          harvest = seqAndWeight => seqAndWeight._1
        )
      )
      .runWith(Sink.collection)

      Await.result(result, 10.seconds) should be (Seq(Seq(1, 2, 3, 4), Seq(5, 6), Seq(7)))
  }

}

class FoldWithinSpec extends StreamSpec(
  AkkaSpec.testConf.withValue(
    "akka.scheduler.implementation",
    ConfigValueFactory.fromAnyRef( "akka.testkit.ExplicitlyTriggeredScheduler")
  )
) {

  def timePasses(amount: FiniteDuration): Unit = {
    system.scheduler match {
      case ets: ExplicitlyTriggeredScheduler => ets.timePasses(amount)
      case other => throw new Exception(s"expecting ${classOf[ExplicitlyTriggeredScheduler]} but got ${other.getClass}")
    }
  }

  def getSystemTimeMs: Long = {
    system.scheduler match {
      case ets: ExplicitlyTriggeredScheduler => ets.currentTimeEpochMs
      case other => throw new Exception(s"expecting ${classOf[ExplicitlyTriggeredScheduler]} but got ${other.getClass}")
    }
  }

  "split aggregator by gap for slow upstream" in {

    val maxGap = 20.seconds

    val p = TestPublisher.probe[Int]()

    val result = Source.fromPublisher(p)
      .via(
        new FoldWithin[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = _ => false,
          harvest = seq => seq,
          maxGap = Some(maxGap), // elements with longer gap will put put to next aggregator
          getSystemTimeMs = getSystemTimeMs
        )
      )
      .runWith(Sink.collection)

    p.sendNext(1)
    timePasses(maxGap/2) // less than maxGap should not cause emit
    p.sendNext(2)
    timePasses(maxGap)

    p.sendNext(3)
    timePasses(maxGap/2) // less than maxGap should not cause emit
    p.sendNext(4)
    timePasses(maxGap)

    p.sendNext(5)
    timePasses(maxGap/2) // less than maxGap should not cause emit
    p.sendNext(6)
    timePasses(maxGap/2) // less than maxGap should not cause emit and it does not accumulate
    p.sendNext(7)
    p.sendComplete()

    Await.result(result, 10.seconds) should be (Seq(Seq(1, 2), Seq(3, 4), Seq(5, 6, 7)))

  }

  "split aggregator by total duration" in {
    val maxDuration = 400.seconds

    val p = TestPublisher.probe[Int]()

    val result = Source.fromPublisher(p)
      .via(
        new FoldWithin[Int, Seq[Int], Seq[Int]](
          seed = i => Seq(i),
          aggregate = (seq, i) => seq :+ i,
          emitOnAgg = _ => false,
          harvest = seq => seq,
          maxDuration = Some(maxDuration), // elements with longer gap will put put to next aggregator
          getSystemTimeMs = getSystemTimeMs
        )
      )
      .runWith(Sink.collection)

    p.sendNext(1)
    timePasses(maxDuration/4)
    p.sendNext(2)
    timePasses(maxDuration/4)
    p.sendNext(3)
    timePasses(maxDuration/4)
    p.sendNext(4)
    timePasses(maxDuration/4) // maxDuration will accumulate and reach threshold here

    p.sendNext(5)
    p.sendNext(6)
    p.sendNext(7)
    p.sendComplete()

    Await.result(result, 10.seconds) should be (Seq(Seq(1, 2, 3, 4), Seq(5, 6, 7)))

  }

  "down stream back pressure should not miss data on completion with pull on start" in {

    val maxGap = 1.second
    val upstream = TestPublisher.probe[Int]()
    val downstream = TestSubscriber.probe[Seq[Int]]()

    Source.fromPublisher(upstream).via(
      new FoldWithin[Int, Seq[Int], Seq[Int]](
        seed = i => Seq(i),
        aggregate = (seq, i) => seq :+ i,
        emitOnAgg = _ => false,
        harvest = seq => seq,
        maxGap = Some(maxGap),
        getSystemTimeMs = getSystemTimeMs
      )
    ).to(Sink.fromSubscriber(downstream)).run()

    downstream.ensureSubscription()
    upstream.sendNext(1) // onPush(1) -> aggregator=Seq(1), due to the preStart pull, will pull upstream again since queue is empty
    timePasses(maxGap) // harvest onTimer, queue=Queue(Seq(1)), aggregator=null
    upstream.sendNext(2) // onPush(2) -> aggregator=Seq(2), due to the previous pull, even the queue is already full at this point due to timer, but it won't pull upstream again
    timePasses(maxGap) // harvest onTimer, queue=(Seq(1), Seq(2)), aggregator=null, note queue size can be 1 more than the threshold
    upstream.sendNext(3) // 3 will not be pushed to the stage until the stage pull upstream
    timePasses(maxGap) // since 3 stayed outside of the stage, this gap will not cause 3 to be emitted
    downstream.request(1).expectNext(Seq(1)) // onPull emit Seq(1), queue=(Seq(2))
    downstream.request(1).expectNext(Seq(2)) // onPull emit Seq(2). queue is empty now, pull upstream and 3 will be pushed into the stage
    // onPush(3) -> aggregator=Seq(3) pull upstream again since queue is empty
    upstream.sendNext(4) // onPush(4) -> aggregator=Seq(3, 4) will follow, and pull upstream again
    downstream.request(1).expectNoMessage()// onPull won't emit since queue is empty, wont't pull upstream again since it's already pulled
    timePasses(maxGap) // harvest onTimer and emit Seq(3, 4) right away since there is pending request, queue becomes empty, aggregator=null
    downstream.expectNext(Seq(3, 4))
    upstream.sendNext(5) // onPush(5) -> aggregator=Seq(5) will happen right after due to the previous pull from onPush(4)
    timePasses(maxGap) // harvest onTimer, queue=(Seq(5)), aggregator=null, no emit since isAvailable(out)=false
    upstream.sendNext(6) // onPush(6) -> aggregator=Seq(6) will happen right after due to the previous pull from onPush(5), even the queue is full at this point
    // upstream.sendNext(7) // if send another element from upstream, thre will be pending push which prevents the upstream from completing and instead the onPull from downstream will come first, this is the behavior of upstream stage
    upstream.sendComplete() // emit remaining queue=Queue(Seq(5)) + harvest and emit aggregator=Seq(6)
    // since there is no pending push from upstream, onUpstreamFinish will be triggered to emit the queu and pending aggregator
    downstream.request(2).expectNext(Seq(5), Seq(6)) // clean up the emit queue and complete downstream
    downstream.expectComplete()
  }
}
