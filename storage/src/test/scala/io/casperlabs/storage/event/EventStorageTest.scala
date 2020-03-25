package io.casperlabs.storage.event

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.info.Event
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.storage.SQLiteFixture
import io.casperlabs.storage.{SQLiteFixture, SQLiteStorage}
import monix.eval.Task
import org.scalatest._

trait EventStorageTest extends FlatSpecLike with Matchers with ArbitraryConsensus {
  def withStorage[R](f: EventStorage[Task] => Task[R]): R

  behavior of "EventStorage"

  // Content doesn't matter here.
  val events = List(
    Event().withBlockAdded(Event.BlockAdded()),
    Event().withNewFinalizedBlock(Event.NewFinalizedBlock()),
    Event().withDeployAdded(Event.DeployAdded()),
    Event().withDeployProcessed(Event.DeployProcessed())
  )

  it should "assign IDs to events stored" in withStorage { db =>
    for {
      ev12 <- db.storeEvents(events.take(2).map(_.value))
      ev34 <- db.storeEvents(events.drop(2).map(_.value))
    } yield {
      ev12(0).eventId shouldBe 1L
      ev12(1).eventId shouldBe 2L
      ev34(0).eventId shouldBe 3L
      ev34(1).eventId shouldBe 4L
    }
  }

  it should "retrieve events from a given ID onwards" in withStorage { db =>
    val values = events.map(_.value)
    for {
      _   <- db.storeEvents(values)
      evs <- db.getEvents(minId = 3).compile.toList
    } yield {
      evs.head.eventId shouldBe 3L
      evs.last.eventId shouldBe 4L
      evs.map(_.value) shouldBe values.drop(2)
    }
  }
}

class SQLiteEventStorageTest extends EventStorageTest with SQLiteFixture[EventStorage[Task]] {
  override def withStorage[R](f: EventStorage[Task] => Task[R]): R = runSQLiteTest[R](f)

  override def db: String = "/tmp/event_storage.db"

  override def createTestResource: Task[EventStorage[Task]] =
    SQLiteStorage.create[Task](readXa = xa, writeXa = xa)
}
