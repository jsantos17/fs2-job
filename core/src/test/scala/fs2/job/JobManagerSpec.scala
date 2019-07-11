/*
 * Copyright 2014–2018 SlamData Inc.
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

package fs2
package job

import scala.Predef.String

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Either, Left, Right}
import scala.{Int, List, Unit}

import java.lang.Exception

import cats.Eq
import cats.effect.IO
import cats.instances.string._
import fs2.concurrent.SignallingRef
import org.specs2.mutable._

object JobManagerSpec extends Specification {
  implicit val cs = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.global)

  def await: Stream[IO, Unit] =
    Stream.sleep(100.milliseconds)

  def mkJobManager: Stream[IO, JobManager[IO, Int, String]] =
    JobManager[IO, Int, String]()

  "Job manager" should {
    "submit a job" in {
      val JobId = 1

      val stream = await.as(Right(1)) ++ Stream(2, 3, 4).map(Right(_)).covary[IO]
      val notification = Stream(Left("done")).covary[IO]

      val job = Job[IO, Int, String, Int](JobId, stream ++ notification)

      val (submitResult, ids, status) = (for {
        mgr <- mkJobManager
        submitResult <- Stream.eval(mgr.submit(job))
        _ <- await
        ids <- Stream.eval(mgr.jobIds)
        status <- Stream.eval(mgr.status(JobId))
      } yield (submitResult, ids, status)).compile.lastOrError.timeout(1.second).unsafeRunSync

      submitResult must beTrue
      ids must_== List(JobId)
      status must beSome(Status.Running)
    }

    "execute a job to completion" in {
      val JobId = 42

      def jobStream(ref: SignallingRef[IO, String]): Stream[IO, Either[String, Int]] =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ await.as(Right(2)) ++ Stream.eval(ref.set("Finished")).as(Right(3))

      val (refAfterStart, refAfterRun) = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        job = Job[IO, Int, String, Int](JobId, jobStream(ref))

        submitStatus <- Stream.eval(mgr.submit(job))
        _ <- latchGet(ref, "Started")
        refAfterStart <- Stream.eval(ref.get)

        _ <- latchGet(ref, "Finished")
        refAfterRun <- Stream.eval(ref.get)
      } yield (refAfterStart, refAfterRun)).compile.lastOrError.timeout(1.second).unsafeRunSync

      refAfterStart mustEqual "Started"
      refAfterRun mustEqual "Finished"
    }

    "cancel a job" in {
      val JobId = 42

      def jobStream(ref: SignallingRef[IO, String]): Stream[IO, Either[String, Int]] =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ await.as(Right(2)) ++ Stream.eval(ref.set("Working")).as(Right(3)) ++ await.as(Right(4)) ++ Stream.eval(ref.set("Finished")).as(Right(5))

      val (statusBeforeCancel, refBeforeCancel, refAfterCancel) = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        job = Job(JobId, jobStream(ref))

        _ <- Stream.eval(mgr.submit(job))
        _ <- latchGet(ref, "Started")

        statusBeforeCancel <- Stream.eval(mgr.status(JobId))
        refBeforeCancel <- Stream.eval(ref.get)

        _ <- latchGet(ref, "Working")
        _ <- Stream.eval(mgr.cancel(JobId))
        _ <- await

        refAfterCancel <- Stream.eval(ref.get)
      } yield (statusBeforeCancel, refBeforeCancel, refAfterCancel)).compile.lastOrError.timeout(1.second).unsafeRunSync


      statusBeforeCancel must beSome(Status.Running)
      refBeforeCancel mustEqual "Started"
      refAfterCancel mustEqual "Working"
    }

    "emit notifications" in {
      val JobId = 42

      val jobStream: Stream[IO, Either[String, Int]] =
        Stream(Right(1), Right(2), Left("50%"), Right(3), Right(4), Left("100%")).covary[IO]

      val ns = (for {
        mgr <- mkJobManager
        submitStatus <- Stream.eval(mgr.submit(Job(JobId, jobStream)))
        _ <- await

        // folds keeps pulling and blocks, even after the stream is done emitting
        ns <- mgr.notifications.take(2).fold(List[String]()) {
          case (acc, elem) => acc :+ elem
        }
      } yield ns).compile.lastOrError.timeout(1.second).unsafeRunSync

      ns mustEqual List("50%", "100%")
    }

    "tapped jobs can be canceled" in {
      val JobId = 42

      def jobStream(ref: SignallingRef[IO, String]): Stream[IO, Either[String, Int]] =
        await.as(Right(1)) ++ Stream.eval(ref.set("Started")).as(Right(2)) ++ await.as(Right(3)) ++ Stream.eval(ref.set("Finished")).as(Right(4))

      val results = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        tappedStream = mgr.tap(Job(JobId, jobStream(ref))).fold(List[Int]()) {
          case (acc, elem) => acc :+ elem
        }
        // sequence tapped stream manually
        _ <- tappedStream.concurrently(latchGet(ref, "Started") ++ Stream.eval(mgr.cancel(JobId)))
        results <- Stream.eval(ref.get)
      } yield results).compile.lastOrError.timeout(1.second).unsafeRunSync

      results mustEqual "Started"
    }

    "not crash when a submitted job crashes" in {
      def jobStream(ref: SignallingRef[IO, String]) =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ await.as(Right(2)) ++ Stream.eval(ref.set("Finished")).as(Right(3))

      val failingJobStream =
        Stream.emit(Right(1)).covary[IO] ++ Stream.raiseError[IO](new Exception("boom")).as(Right(2)) ++ Stream.emit(Right(3)).covary[IO]

      val results = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        _ <- Stream.eval(mgr.submit(Job(1, jobStream(ref))))
        _ <- Stream.eval(mgr.submit(Job(2, failingJobStream)))
        _ <- latchGet(ref, "Finished")
        results <- Stream.eval(ref.get)
      } yield results).compile.lastOrError.timeout(1.second).unsafeRunSync

      results mustEqual "Finished"
    }

    "not crash when a tapped job crashes" in {
      def jobStream(ref: SignallingRef[IO, String]) =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ await.as(Right(2)) ++ Stream.eval(ref.set("Finished")).as(Right(3))

      val failingJobStream =
        Stream.emit(Right(1)).covary[IO] ++ Stream.raiseError[IO](new Exception("boom")).as(Right(2)) ++ Stream.emit(Right(3)).covary[IO]

      val results = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        _ <- Stream.eval(mgr.submit(Job(1, jobStream(ref))))
        // boom
        _ <- mgr.tap(Job(2, failingJobStream))
        _ <- latchGet(ref, "Finished")
        results <- Stream.eval(ref.get)
      } yield results).compile.lastOrError.timeout(1.second).unsafeRunSync

      results mustEqual "Finished"
    }

    "report errors when a job crashes" in {
      val JobId = 1

      def jobStream(ref: SignallingRef[IO, String]) =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ Stream.raiseError[IO](new Exception("boom")) ++ Stream.eval(ref.set("Finished")).as(Right(3))

      val event = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        _ <- Stream.eval(mgr.submit(Job(JobId, jobStream(ref))))
        _ <- latchGet(ref, "Started")
        event <- mgr.events.take(1)
      } yield event).compile.lastOrError.timeout(1.second).unsafeRunSync

      event must beLike {
        case Event.Failed(i, _, throwable) => {
          i mustEqual JobId
          throwable.getMessage mustEqual "boom"
        }
      }
    }

    "report when a job completes" in {
      val JobId = 1

      def jobStream(ref: SignallingRef[IO, String]) =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ Stream.eval(ref.set("Finished")).as(Right(2))

      val event = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        _ <- Stream.eval(mgr.submit(Job(JobId, jobStream(ref))))
        _ <- latchGet(ref, "Finished")
        event <- mgr.events.take(1)
      } yield event).compile.lastOrError.timeout(1.second).unsafeRunSync

      event must beLike {
        case Event.Completed(i, _) => i mustEqual JobId
      }
    }

    "report both failing and completing jobs" in {
      val JobId = 1
      val FailingJobId = 2

      def jobStream(ref: SignallingRef[IO, String]) =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ await.as(Right(2)) ++ Stream.eval(ref.set("Finished")).as(Right(3))

      val failingJobStream =
        Stream.emit(Right(1)).covary[IO] ++ Stream.raiseError[IO](new Exception("boom")).as(Right(2)) ++ Stream.emit(Right(3)).covary[IO]

      val events = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        _ <- Stream.eval(mgr.submit(Job(JobId, jobStream(ref))))
        _ <- Stream.eval(mgr.submit(Job(FailingJobId, failingJobStream)))
        _ <- latchGet(ref, "Finished")
        events <- mgr.events.take(2).fold(List[Event[Int]]()) {
          case (acc, elem) => acc :+ elem
        }
      } yield events).compile.lastOrError.timeout(1.second).unsafeRunSync

      events must have size(2)
      events must contain((event: Event[Int]) => event must beLike {
        case Event.Completed(i, _) => i mustEqual JobId
      }).exactly(1.times)

      events must contain((event: Event[Int]) => event must beLike {
        case Event.Failed(i, _, ex) =>
          i mustEqual FailingJobId
          ex.getMessage mustEqual "boom"
      }).exactly(1.times)
    }
  }

  // blocks until s.get === expected
  def latchGet(s: SignallingRef[IO, String], expected: String): Stream[IO, Unit] =
    Stream.eval(
      s.discrete.filter(Eq[String].eqv(_, expected)).take(1).compile.drain)
}
