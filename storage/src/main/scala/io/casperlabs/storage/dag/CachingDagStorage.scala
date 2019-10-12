package io.casperlabs.storage.dag

import cats._
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.google.common.cache.{Cache, CacheBuilder}
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{MeteredDagRepresentation, MeteredDagStorage}

class CachingDagStorage[F[_]: Sync](
    // How far to go to the past (by ranks) for caching neighbourhood of looked up block
    neighbourhoodBefore: Int,
    // How far to go to the future (by ranks) for caching neighbourhood of looked up block
    neighbourhoodAfter: Int,
    underlying: DagStorage[F] with DagRepresentation[F],
    private[dag] val childrenCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val justificationCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val messagesCache: Cache[BlockHash, Message],
    semaphore: Semaphore[F]
) extends DagStorage[F]
    with DagRepresentation[F] {
  private def cacheOrUnderlying[A](fromCache: => Option[A], fromUnderlying: F[A]) =
    Sync[F].delay(fromCache) flatMap {
      case None    => fromUnderlying
      case Some(a) => a.pure[F]
    }

  private def cacheOrUnderlyingOpt[A](fromCache: => Option[A], fromUnderlying: F[Option[A]]) =
    Sync[F].delay(fromCache) flatMap {
      case None        => fromUnderlying
      case s @ Some(_) => (s: Option[A]).pure[F]
    }

  private def cacheMessage(message: Message): F[Unit] = Sync[F].delay {
    val parents        = message.parents
    val justifications = message.justifications.map(_.latestBlockHash)
    parents.foreach { parent =>
      val newChildren = Option(childrenCache.getIfPresent(parent))
        .getOrElse(Set.empty[BlockHash]) + message.messageHash
      childrenCache.put(parent, newChildren)
    }
    justifications.foreach { justification =>
      val newBlockHashes = Option(justificationCache.getIfPresent(justification))
        .getOrElse(Set.empty[BlockHash]) + message.messageHash
      justificationCache.put(justification, newBlockHashes)
    }
    messagesCache.put(message.messageHash, message)
  }

  private def cacheSummary(summary: BlockSummary): F[Unit] =
    Sync[F].fromTry(Message.fromBlockSummary(summary)).flatMap(cacheMessage)

  private def cacheNeighbourhood(message: Message): F[Unit] =
    topoSort(
      startBlockNumber = message.rank - neighbourhoodBefore,
      endBlockNumber = message.rank + neighbourhoodAfter
    ).compile.toList.flatMap(summaries => summaries.flatten.traverse_(cacheSummary))

  override def children(blockHash: BlockHash): F[Set[BlockHash]] =
    cacheOrUnderlying(
      Option(childrenCache.getIfPresent(blockHash)),
      underlying.children(blockHash)
    )

  /** Return blocks that having a specify justification */
  override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
    cacheOrUnderlying(
      Option(justificationCache.getIfPresent(blockHash)),
      underlying.justificationToBlocks(blockHash)
    )

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]

  override private[storage] def insert(block: Block): F[DagRepresentation[F]] =
    for {
      dag     <- underlying.insert(block)
      message <- Sync[F].fromTry(Message.fromBlock(block))
      _       <- semaphore.withPermit(cacheMessage(message))
    } yield dag

  override def checkpoint(): F[Unit] = underlying.checkpoint()

  override def clear(): F[Unit] =
    Sync[F].delay {
      childrenCache.invalidateAll()
      justificationCache.invalidateAll()
    } >> underlying.clear()

  override def close(): F[Unit] = underlying.close()

  override def lookup(blockHash: BlockHash): F[Option[Message]] =
    cacheOrUnderlyingOpt(
      Option(messagesCache.getIfPresent(blockHash)),
      underlying
        .lookup(blockHash)
        .flatMap(
          maybeMessage => maybeMessage.traverse_(cacheNeighbourhood) >> maybeMessage.pure[F]
        )
    )

  override def contains(blockHash: BlockHash): F[Boolean] =
    lookup(blockHash)
      .map(_.isDefined)
      .ifM(true.pure[F], underlying.contains(blockHash))

  /** Return the ranks of blocks in the DAG between start and end, inclusive. */
  override def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockSummary]] = underlying.topoSort(startBlockNumber, endBlockNumber)

  /** Return ranks of blocks in the DAG from a start index to the end. */
  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockSummary]] =
    underlying.topoSort(startBlockNumber)

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockSummary]] =
    underlying.topoSortTail(tailLength)

  override def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
    underlying.latestMessageHash(validator)

  override def latestMessage(validator: Validator): F[Option[Message]] =
    underlying.latestMessage(validator)

  override def latestMessageHashes: F[Map[Validator, BlockHash]] = underlying.latestMessageHashes

  override def latestMessages: F[Map[Validator, Message]] = underlying.latestMessages
}

object CachingDagStorage {
  def apply[F[_]: Concurrent: Metrics](
      underlying: DagStorage[F] with DagRepresentation[F],
      maxSizeBytes: Long,
      // How far to go to the past (by ranks) for caching neighbourhood of looked up block
      neighbourhoodBefore: Int,
      // How far to go to the future (by ranks) for caching neighbourhood of looked up block
      neighbourhoodAfter: Int,
      name: String = "cache"
  ): F[CachingDagStorage[F]] = {
    val metricsF = Metrics[F]
    val createBlockHashesSetCache = Sync[F].delay {
      CacheBuilder
        .newBuilder()
        .maximumWeight(maxSizeBytes)
        // Assuming block hashes 32 bytes long
        .weigher((_: BlockHash, values: Set[BlockHash]) => (values.size + 1) * 32)
        .build[BlockHash, Set[BlockHash]]()
    }

    val createMessagesCache = Sync[F].delay {
      CacheBuilder
        .newBuilder()
        .maximumWeight(maxSizeBytes)
        .weigher((_: BlockHash, msg: Message) => msg.blockSummary.serializedSize) //TODO: Fix the size estimate of a message.
        .build[BlockHash, Message]()
    }

    for {
      childrenCache      <- createBlockHashesSetCache
      justificationCache <- createBlockHashesSetCache
      messagesCache      <- createMessagesCache
      semaphore          <- Semaphore[F](1)
      store = new CachingDagStorage[F](
        neighbourhoodBefore,
        neighbourhoodAfter,
        underlying,
        childrenCache,
        justificationCache,
        messagesCache,
        semaphore
      ) with MeteredDagStorage[F] with MeteredDagRepresentation[F] {
        override implicit val m: Metrics[F] = metricsF
        override implicit val ms: Metrics.Source =
          Metrics.Source(DagStorageMetricsSource, name)
        override implicit val a: Apply[F] = Sync[F]
      }
    } yield store
  }
}
